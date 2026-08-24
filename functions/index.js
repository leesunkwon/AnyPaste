const {initializeApp} = require("firebase-admin/app");
const {getFirestore, Timestamp, FieldValue} = require("firebase-admin/firestore");
const {getMessaging} = require("firebase-admin/messaging");
const {getStorage} = require("firebase-admin/storage");
const {logger} = require("firebase-functions");
const {
  onDocumentCreated,
  onDocumentDeleted,
} = require("firebase-functions/v2/firestore");
const {onSchedule} = require("firebase-functions/v2/scheduler");
const {onCall, HttpsError} = require("firebase-functions/v2/https");

initializeApp();

const REGION = "asia-northeast3";
const DEFAULT_CLIPBOARD_TTL_MILLIS = 24 * 60 * 60 * 1000;
const MAX_RECENT_DEVICE_DOCUMENTS = 100;
const MAX_FCM_TOKEN_LENGTH = 4096;
const MAX_FCM_ITEM_ID_LENGTH = 128;
const MAX_FCM_DEVICE_ID_LENGTH = 128;
const MAX_FCM_FILE_NAME_LENGTH = 180;
const MAX_FCM_SEND_ATTEMPTS = 3;
const INITIAL_FCM_RETRY_DELAY_MILLIS = 500;
const EXPIRED_QUERY_BATCH_SIZE = 400;
const MAX_EXPIRED_BATCHES_PER_RUN = 5;
const MAX_DEVICE_ID_LENGTH = 128;
const SESSION_REVOKED_EVENT = "sessionRevoked";

/**
 * Disconnects one registered device without revoking every session for the user.
 *
 * Firebase Auth can only revoke refresh tokens for an entire user.  A per-device
 * revocation record therefore acts as this app's device session allow-list: client
 * registration, heartbeats and sends are blocked after the record is created.
 */
exports.revokeDeviceSession = onCall(
    {
      region: REGION,
    },
    async (request) => {
      const userId = request.auth?.uid;
      if (!userId) {
        throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
      }

      const deviceId = boundedString(request.data?.deviceId, MAX_DEVICE_ID_LENGTH);
      if (!deviceId || deviceId.includes("/")) {
        throw new HttpsError("invalid-argument", "기기 ID 형식이 올바르지 않습니다.");
      }

      const firestore = getFirestore();
      const deviceReference = firestore
          .collection("users")
          .doc(userId)
          .collection("devices")
          .doc(deviceId);
      const revokedReference = firestore
          .collection("users")
          .doc(userId)
          .collection("revokedDevices")
          .doc(deviceId);

      const token = await firestore.runTransaction(async (transaction) => {
        const device = await transaction.get(deviceReference);
        if (!device.exists) {
          throw new HttpsError("not-found", "연결된 기기를 찾을 수 없습니다.");
        }
        const fcmToken = boundedString(device.get("fcmToken"), MAX_FCM_TOKEN_LENGTH);
        transaction.set(revokedReference, {
          revokedAt: FieldValue.serverTimestamp(),
        });
        transaction.delete(deviceReference);
        return fcmToken;
      });

      if (token) {
        try {
          await getMessaging().send({
            token,
            data: {
              event: SESSION_REVOKED_EVENT,
              deviceId,
            },
            android: {priority: "high"},
          });
        } catch (error) {
          // The persisted revocation record is authoritative. A failed push is
          // recovered by the device's next heartbeat or app launch.
          logger.warn("Failed to send a device session revocation", {
            userId,
            deviceId,
            error,
          });
        }
      }

      logger.info("Device session revoked", {userId, deviceId});
      return {revoked: true};
    },
);

exports.notifyClipboardCreated = onDocumentCreated(
    {
      document: "users/{userId}/clipboard/{itemId}",
      region: REGION,
      retry: true,
    },
    async (event) => {
      const createdSnapshot = event.data;
      if (!createdSnapshot) return;

      const {userId, itemId} = event.params;
      const currentSnapshot = await createdSnapshot.ref.get();
      if (!currentSnapshot.exists) {
        logger.info("Skipped a deleted clipboard notification", {userId, itemId});
        return;
      }
      const item = currentSnapshot.data();
      const targetDeviceId = boundedString(
          item.targetDeviceId,
          MAX_FCM_DEVICE_ID_LENGTH,
      );
      const recipientDocuments = await loadRecipientDeviceDocuments(
          userId,
          targetDeviceId,
      );
      const tokens = [...new Set(recipientDocuments
          .filter((document) => document.id !== item.sourceDeviceId)
          .map((document) => document.get("fcmToken"))
          .filter((token) => typeof token === "string" && token.length > 0)
          .filter((token) => token.length <= MAX_FCM_TOKEN_LENGTH))];

      if (tokens.length === 0) {
        logger.info("No recipient tokens", {userId, itemId});
        return;
      }

      if (typeof item.expiresAt?.toMillis !== "function") {
        logger.warn("Skipped a clipboard item without a valid expiry", {userId, itemId});
        return;
      }
      const expiresAtMillis = item.expiresAt.toMillis();
      const messageTtlMillis = Math.floor(Math.min(
          DEFAULT_CLIPBOARD_TTL_MILLIS,
          Math.max(0, expiresAtMillis - Date.now()),
      ));
      if (messageTtlMillis === 0) {
        logger.info("Skipped an expired clipboard notification", {userId, itemId});
        return;
      }

      const type = ["text", "image", "file"].includes(item.type) ? item.type : "file";
      const sendOutcome = await sendMulticastWithRetry(tokens, {
        data: {
          itemId: boundedString(itemId, MAX_FCM_ITEM_ID_LENGTH),
          type,
          sourceDeviceId: boundedString(
              item.sourceDeviceId,
              MAX_FCM_DEVICE_ID_LENGTH,
          ),
          fileName: boundedString(item.fileName, MAX_FCM_FILE_NAME_LENGTH),
          expiresAtEpochMs: String(expiresAtMillis),
        },
        android: {
          priority: "high",
          ttl: messageTtlMillis,
        },
      });

      if (sendOutcome.invalidTokens.size > 0) {
        try {
          const batch = getFirestore().batch();
          recipientDocuments.forEach((document) => {
            if (sendOutcome.invalidTokens.has(document.get("fcmToken"))) {
              batch.update(document.ref, {fcmToken: ""});
            }
          });
          await batch.commit();
        } catch (error) {
          // A token cleanup failure must not retry an already-delivered multicast.
          logger.error("Failed to clear invalid FCM tokens", {
            userId,
            itemId,
            error,
          });
        }
      }

      logger.info("Clipboard notification delivered", {
        userId,
        itemId,
        successCount: sendOutcome.successCount,
        failureCount: sendOutcome.failureCount,
        attemptCount: sendOutcome.attemptCount,
      });
    },
);

exports.cleanupClipboardStorage = onDocumentDeleted(
    {
      document: "users/{userId}/clipboard/{itemId}",
      region: REGION,
      retry: true,
    },
    async (event) => {
      const item = event.data?.data();
      const storagePath = item?.storagePath;
      if (typeof storagePath !== "string" || storagePath.length === 0) return;

      const {userId, itemId} = event.params;
      const expectedPrefix = `users/${userId}/clipboard/${itemId}/`;
      if (!storagePath.startsWith(expectedPrefix)) {
        logger.warn("Skipped an unexpected clipboard storage path", {
          userId,
          itemId,
          storagePath,
        });
        return;
      }

      await getStorage().bucket().file(storagePath).delete({ignoreNotFound: true});
      logger.info("Deleted clipboard storage object", {userId, itemId});
    },
);

exports.cleanupExpiredClipboardDocuments = onSchedule(
    {
      schedule: "0 * * * *",
      timeZone: "Asia/Seoul",
      region: REGION,
      retryCount: 3,
      minBackoffSeconds: 60,
      maxBackoffSeconds: 600,
      maxRetrySeconds: 3600,
      maxInstances: 1,
      timeoutSeconds: 540,
    },
    async () => {
      const firestore = getFirestore();
      const expiryCutoff = Timestamp.now();
      let deletedCount = 0;
      let batchCount = 0;

      while (batchCount < MAX_EXPIRED_BATCHES_PER_RUN) {
        const snapshot = await firestore
            .collectionGroup("clipboard")
            .where("expiresAt", "<=", expiryCutoff)
            .limit(EXPIRED_QUERY_BATCH_SIZE)
            .get();
        if (snapshot.empty) break;

        const batch = firestore.batch();
        snapshot.docs.forEach((document) => batch.delete(document.ref));
        await batch.commit();

        deletedCount += snapshot.size;
        batchCount += 1;
        if (snapshot.size < EXPIRED_QUERY_BATCH_SIZE) break;
      }

      logger.info("Expired clipboard cleanup completed", {
        deletedCount,
        batchCount,
        capped: batchCount === MAX_EXPIRED_BATCHES_PER_RUN,
      });
    },
);

async function loadRecipientDeviceDocuments(userId, targetDeviceId) {
  const devices = getFirestore()
      .collection("users")
      .doc(userId)
      .collection("devices");

  if (targetDeviceId) {
    const target = await devices.doc(targetDeviceId).get();
    return target.exists ? [target] : [];
  }

  const recentDevices = await devices
      .orderBy("lastSeenAt", "desc")
      .limit(MAX_RECENT_DEVICE_DOCUMENTS)
      .get();
  return recentDevices.docs;
}

async function sendMulticastWithRetry(tokens, multicastPayload) {
  let pendingTokens = tokens;
  let successCount = 0;
  let failureCount = 0;
  let attemptCount = 0;
  const invalidTokens = new Set();

  while (pendingTokens.length > 0 && attemptCount < MAX_FCM_SEND_ATTEMPTS) {
    attemptCount += 1;
    const result = await getMessaging().sendEachForMulticast({
      ...multicastPayload,
      tokens: pendingTokens,
    });
    const retryTokens = [];

    result.responses.forEach((response, index) => {
      const token = pendingTokens[index];
      if (response.success) {
        successCount += 1;
      } else if (isInvalidTokenError(response.error?.code)) {
        invalidTokens.add(token);
        failureCount += 1;
      } else if (
        isRetryableMessagingError(response.error?.code) &&
        attemptCount < MAX_FCM_SEND_ATTEMPTS
      ) {
        retryTokens.push(token);
      } else {
        failureCount += 1;
      }
    });

    pendingTokens = retryTokens;
    if (pendingTokens.length > 0) {
      const retryDelay = INITIAL_FCM_RETRY_DELAY_MILLIS *
        Math.pow(2, attemptCount - 1);
      logger.warn("Retrying transient FCM delivery failures", {
        attemptCount,
        retryCount: pendingTokens.length,
      });
      await delay(retryDelay);
    }
  }

  return {
    successCount,
    failureCount,
    attemptCount,
    invalidTokens,
  };
}

function boundedString(value, maximumLength) {
  if (typeof value !== "string") return "";
  return Array.from(value).slice(0, maximumLength).join("");
}

function isInvalidTokenError(code) {
  return code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered";
}

function isRetryableMessagingError(code) {
  return code === "messaging/internal-error" ||
    code === "messaging/server-unavailable" ||
    code === "messaging/unknown-error" ||
    code === "messaging/quota-exceeded" ||
    code === "messaging/unavailable";
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
