# AnyPaste Firebase 설정

`google-services.json`은 환경별 Firebase 프로젝트 식별 정보가 들어가는 로컬 파일이므로 Git에 올리지 않습니다. 새로 클론한 환경에서는 Firebase Console에서 Android 앱(`com.kotlinsun.anypaste`)의 파일을 다시 받아 `app/google-services.json`에 둡니다.

## Console에서 필요한 설정

1. Authentication에서 **이메일/비밀번호** 제공업체를 활성화합니다.
2. Google 로그인을 사용할 경우 **Google** 제공업체를 활성화합니다.
3. Android 앱에 개발/배포 인증서의 SHA-1과 SHA-256을 등록합니다.
4. Web OAuth Client가 포함된 `google-services.json`을 다시 내려받습니다.
5. Firestore Database와 Cloud Storage를 생성합니다.

현재 로컬 파일에는 Web OAuth Client가 없어 이메일 로그인만 사용할 수 있습니다. 앱은 이 상태에서 Google 로그인 버튼을 누르면 설정 안내를 표시하며 종료되지 않습니다.

## 보안 규칙과 푸시 알림 배포

저장소 루트에서 Firebase CLI 로그인 및 프로젝트 선택을 마친 뒤 다음 구성을 배포합니다.

```bash
firebase use <project-id>
firebase deploy --only firestore:rules,firestore:indexes,storage
cd functions
npm install
cd ..
firebase deploy --only functions
```

Firestore 규칙은 사용자별 문서 접근과 허용 필드를 제한합니다. 클립보드 문서는 생성 시점보다 미래이면서 25시간 이내인 `expiresAt`만 허용합니다. 앱은 기본값으로 생성 시점부터 24시간을 사용합니다. 기존 프로젝트에 규칙과 다른 형태의 문서가 있다면 배포 전에 마이그레이션해야 합니다.

새 클립보드 문서가 생성되면 Cloud Function이 발신 기기를 제외한 수신 기기에 FCM data 메시지를 보냅니다. 전체 기기 전송은 `lastSeenAt` 기준 최근 100대로 제한하며, 특정 `targetDeviceId`가 있으면 해당 기기만 조회합니다. 일시적인 FCM 오류는 실패한 토큰만 최대 3회 시도하고, 만료되거나 잘못된 토큰은 기기 문서에서 비웁니다.

Firestore TTL은 `expiresAt` 필드를 사용합니다. 별도의 예약 함수도 매시간 만료 문서를 최대 2,000개씩 정리하며, 문서 삭제 트리거가 연결된 Storage 객체를 삭제합니다. 예약 함수 배포에는 Blaze 요금제와 Cloud Scheduler API 등 Google Cloud API 활성화가 필요할 수 있습니다. `firestore.indexes.json`에는 앱의 사용자별 만료 쿼리와 예약 함수의 collection group 쿼리를 위한 두 인덱스 범위가 모두 포함되어 있습니다.

## Storage 고아 파일 안전망

Storage 규칙은 `users/{userId}/clipboard/{itemId}/{fileName}` 위치의 신규 업로드만 허용하고 기존 객체 덮어쓰기를 막습니다. 파일 크기는 50MB 이하, MIME 타입과 `originalFileName` 메타데이터가 유효해야 합니다.

업로드가 완료된 뒤 Firestore 문서 생성 전에 앱이 중단되면 삭제 트리거가 실행되지 않습니다. 루트의 `storage.lifecycle.json`은 이런 고아 파일을 포함해 `users/` 아래에서 생성 후 2일이 지난 객체를 삭제하는 안전망입니다. Firebase Console의 Storage 버킷 이름으로 아래 placeholder를 바꿔 별도로 적용합니다.

```bash
gcloud auth login
gcloud config set project <YOUR_FIREBASE_PROJECT_ID>
gcloud storage buckets update gs://<YOUR_FIREBASE_STORAGE_BUCKET> \
  --lifecycle-file=storage.lifecycle.json
```

예를 들어 버킷 이름은 프로젝트 설정에 따라 `<project-id>.firebasestorage.app` 또는 기존 프로젝트의 `<project-id>.appspot.com` 형태입니다. `<YOUR_FIREBASE_STORAGE_BUCKET>`에는 `gs://`를 제외한 이름만 넣습니다. Firebase CLI의 `firebase deploy` 명령은 버킷 lifecycle을 배포하지 않으므로 위 `gcloud` 명령을 따로 실행해야 합니다.

현재 lifecycle은 `users/` 아래 모든 객체에 적용됩니다. 향후 2일 이상 보관해야 하는 파일을 같은 경로에 추가한다면 별도 prefix를 사용하거나 정책을 조정해야 합니다.

## Android 제약

Android 10 이상에서는 다른 앱이 백그라운드에 있는 동안 일반 앱이 클립보드를 읽을 수 없습니다. AnyPaste는 사용자가 켠 foreground data-sync service와 앱 복귀 시점 캡처, Android 공유 메뉴를 함께 사용합니다. 이는 운영체제 제한을 우회하지 않는 구현입니다.
