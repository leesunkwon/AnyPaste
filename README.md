# AnyPaste

macOS와 Android 사이에서 텍스트·이미지·파일을 빠르게 전송하는 개인용 클립보드 동기화 앱입니다.

> 전송 데이터는 **최신 항목 1개만 유지**합니다. 새 항목을 보내면 기존 항목은 즉시 삭제되고, 남은 항목도 생성 후 **5분**이 지나면 만료됩니다.

## 주요 기능

- macOS ↔ Android 간 텍스트, 이미지, 파일 전송
- 전체 기기 또는 특정 기기를 선택한 전송
- 이미지 미리보기와 파일 다운로드·열기
- 기기 연결 상태, 전달·읽음 상태 확인
- 기기 이름 변경과 연결 해제
- 이메일/비밀번호 로그인 및 Android Google 로그인
- Android 공유 메뉴와 클립보드 자동 동기화
- 전송 실패 원인 안내와 재시도 대기 목록

## 지원 환경

| 플랫폼 | 요구 사항 |
| --- | --- |
| Android | Android 10(API 29) 이상 |
| macOS | macOS 14 이상 |
| 백엔드 | Firebase Authentication, Firestore, Cloud Storage, Cloud Functions, FCM |

## 프로젝트 구조

```text
.
├── AnyPaste_ForAndroid/AnyPaste/    # Android 앱(Kotlin, XML)
├── AnyPaste_ForMac/AnyPaste/        # macOS 앱(SwiftUI)
├── functions/                       # Firebase Cloud Functions(Node.js 22)
├── firestore.rules                  # Firestore 보안 규칙
├── storage.rules                    # Cloud Storage 보안 규칙
├── firestore.indexes.json           # Firestore 인덱스
└── storage.lifecycle.json           # Storage 고아 파일 정리 정책
```

## 시작하기

### 1. Firebase 프로젝트 준비

Firebase Console에서 아래 서비스를 활성화합니다.

1. **Authentication**: 이메일/비밀번호 제공업체 활성화
2. **Authentication**: Google 로그인을 사용할 경우 Google 제공업체 활성화
3. **Firestore Database** 생성
4. **Cloud Storage** 생성
5. **Cloud Messaging** 설정

Android 패키지 이름은 `com.kotlinsun.anypaste`입니다. Google 로그인을 사용한다면 개발·배포 인증서의 SHA-1 및 SHA-256도 Android 앱 설정에 등록해야 합니다.

### 2. 로컬 비밀 설정

실제 설정 파일은 Git에 올리지 않습니다.

#### Android

Firebase Console에서 내려받은 `google-services.json`을 아래 위치에 둡니다.

```text
AnyPaste_ForAndroid/AnyPaste/app/google-services.json
```

#### macOS

예시 파일을 복사해 실제 Firebase 값을 입력합니다.

```bash
cp AnyPaste_ForMac/AnyPaste/Secrets.xcconfig.example \
   AnyPaste_ForMac/AnyPaste/Secrets.xcconfig
```

`Secrets.xcconfig`에는 다음 값을 설정합니다.

```xcconfig
FIREBASE_API_KEY = ...
FIREBASE_PROJECT_ID = ...
FIREBASE_STORAGE_BUCKET = ...
```

`Secrets.xcconfig`은 `Config.xcconfig`에서 빌드 시점에만 불러오며, 실제 값은 앱 소스나 Git에 기록하지 않습니다.

### 3. Firebase 규칙과 Functions 배포

저장소 루트에서 Firebase CLI 로그인 및 프로젝트 선택을 마친 뒤 실행합니다.

```bash
firebase use <project-id>
firebase deploy --only firestore:rules,firestore:indexes,storage
npm --prefix functions install
firebase deploy --only functions
```

Cloud Functions는 새 항목을 수신 기기에 FCM data 메시지로 알리고, 만료된 클립보드 문서와 연결된 파일을 정리합니다. 예약 정리 작업을 사용하려면 Firebase Blaze 요금제와 Cloud Scheduler 관련 API 활성화가 필요할 수 있습니다.

`storage.lifecycle.json`의 2일 경과 고아 파일 정리 정책은 Firebase CLI로 배포되지 않으므로, 필요하면 별도로 적용합니다.

```bash
gcloud auth login
gcloud config set project <YOUR_FIREBASE_PROJECT_ID>
gcloud storage buckets update gs://<YOUR_FIREBASE_STORAGE_BUCKET> \
  --lifecycle-file=storage.lifecycle.json
```

자세한 Firebase 설정과 Storage 정책은 [FIREBASE_SETUP.md](AnyPaste_ForAndroid/AnyPaste/FIREBASE_SETUP.md)에서 확인할 수 있습니다.

## 앱 실행

### Android

Android Studio에서 `AnyPaste_ForAndroid/AnyPaste`를 열고 실행하거나, 해당 디렉터리에서 다음 명령을 실행합니다.

```bash
./gradlew :app:assembleDebug
```

### macOS

Xcode에서 `AnyPaste_ForMac/AnyPaste/AnyPaste.xcodeproj`를 열고 `AnyPaste` 스킴을 실행합니다. `Secrets.xcconfig` 설정이 없으면 Firebase 연결 기능을 사용할 수 없습니다.

## 동기화와 보관 정책

| 항목 | 정책 |
| --- | --- |
| 보관 개수 | 계정별 최신 항목 1개 |
| 보관 시간 | 생성 후 5분 |
| 새 항목 전송 | 기존 항목 및 연결 파일 즉시 삭제 |
| 파일 크기 | 최대 50MB |
| 전송 대상 | 전체 기기 또는 특정 기기 |

만료 시각은 Firestore 규칙에서도 최대 5분으로 제한됩니다. Firestore TTL 및 예약 정리 작업은 실제 서버 데이터 삭제 시점에 약간의 지연이 있을 수 있지만, 앱은 만료된 항목을 표시하거나 수신하지 않습니다.

## Android 자동 동기화 안내

Android는 운영체제 정책상 일반 앱이 백그라운드에서 다른 앱의 클립보드를 자유롭게 읽을 수 없습니다. 자동 동기화를 켜면 AnyPaste는 foreground data-sync service와 앱 복귀 시점 캡처를 사용합니다.

- 앱이 실행 중이거나 foreground service가 유지되는 경우 자동 동기화가 동작합니다.
- 알림 권한 거부, 배터리 최적화, 절전 모드, 앱 강제 종료 상태에서는 동기화가 늦어지거나 중단될 수 있습니다.
- 파일과 이미지는 Android 공유 메뉴 또는 앱의 보내기 화면에서 수동 전송할 수 있습니다.

## 보안

- Firebase Authentication으로 사용자별 접근을 분리합니다.
- Firestore와 Cloud Storage 규칙은 사용자 경로, 입력 필드, 파일 경로와 크기를 검증합니다.
- 기기 연결 해제 시 해당 기기의 세션과 FCM 토큰을 무효화합니다.
- `google-services.json`, `Secrets.xcconfig`, `.env`, Xcode 개인 UI 상태 파일은 `.gitignore`로 제외합니다.

Firebase 클라이언트 API 키는 앱 동작에 포함될 수 있는 식별자이므로, Google Cloud Console에서 사용 API와 앱 제한을 설정해 사용 범위를 제한하세요.

## 기술 스택 및 라이브러리

### Android

Kotlin과 XML 기반 Android Views로 구현했습니다. `MainViewModel`이 `StateFlow`로 화면 상태를 관리하고, 화면은 `Fragment` 단위로 전환해 하단 네비게이션을 유지합니다.

| 분류 | 라이브러리 / 버전 | 사용 목적 |
| --- | --- | --- |
| 언어·빌드 | Kotlin, Android Gradle Plugin 9.2.1 | Android 앱 구현과 빌드 구성 |
| UI | Android Views(XML), Material Components 1.14.0 | 입력 필드, 스위치, 버튼 등 일관된 네이티브 UI 구성 |
| 화면 구성 | AndroidX AppCompat 1.8.0, Activity KTX 1.13.0, Fragment | Fragment 기반 화면 전환과 Lifecycle 연동 |
| 레이아웃 | ConstraintLayout 2.2.2 | 복합 화면의 반응형 제약 레이아웃 구성 |
| 비동기 처리 | Kotlin Coroutines 1.10.2 | 네트워크·파일 I/O와 UI 상태 갱신을 안전하게 분리 |
| 상태 관리 | ViewModel, StateFlow | 인증·기기·전송·재시도 상태를 단일 UI 상태로 관리 |
| 로그인 | Credential Manager 1.6.0, Google ID 1.1.1 | Google 계정 선택과 Firebase 인증 연동 |
| Firebase | Firebase BoM 34.17.0 | Firebase SDK 버전을 일관되게 관리 |
| Firebase Auth | `firebase-auth` | 이메일/비밀번호 및 Google 로그인 |
| Firestore | `firebase-firestore` | 기기 정보, 전송 항목, 전달·읽음 상태 실시간 동기화 |
| Cloud Storage | `firebase-storage` | 이미지·파일 업로드와 다운로드 진행 상태 처리 |
| Cloud Functions | `firebase-functions` | 기기 연결 해제에 따른 세션 무효화 호출 |
| FCM | `firebase-messaging` | 새 전송 항목 알림과 수신 동기화 트리거 |

### macOS

SwiftUI를 중심으로 구현했으며, 외부 Firebase SDK 대신 `URLSession` 기반 `FirebaseRESTClient`를 직접 작성해 Authentication, Firestore, Storage, Cloud Functions API를 호출합니다. 의존성을 가볍게 유지하면서도 `actor`로 네트워크 요청의 동시성 안전성을 확보했습니다.

| 분류 | 프레임워크 | 사용 목적 |
| --- | --- | --- |
| UI | SwiftUI | 앱 화면, 사이드바, 전송·기기·설정 인터페이스 구성 |
| 상태 관리 | Combine, `ObservableObject`, `@Published` | `AppModel` 중심의 화면 상태 갱신 |
| 네트워크 | Foundation, `URLSession` | Firebase REST API 호출과 파일 업로드·다운로드 |
| 동시성 | Swift Concurrency, `actor` | 인증 토큰 갱신과 REST 요청의 경쟁 상태 방지 |
| 클립보드 | AppKit, `NSPasteboard` | macOS 클립보드 감시와 수신 데이터 반영 |
| 중복 방지 | CryptoKit, SHA-256 | 동일 클립보드 데이터 재전송 및 수신-재전송 루프 방지 |
| 보안 저장소 | Security, Keychain | Firebase 로그인 세션을 기기 전용 Keychain에 저장 |
| 알림 | UserNotifications | 수신 항목에 대한 로컬 알림 표시 |
| 파일 형식 | UniformTypeIdentifiers | 파일 확장자·MIME 타입 판별과 이미지 분류 |

### Backend 및 Firebase

| 분류 | 라이브러리 / 서비스 | 사용 목적 |
| --- | --- | --- |
| 런타임 | Node.js 22 | Cloud Functions 실행 환경 |
| 서버 SDK | `firebase-admin` 14.3.0 | Admin 권한의 Firestore 조회, FCM 발송, Storage 정리 |
| Functions | `firebase-functions` 7.3.2 | Callable Function, Firestore 트리거, 예약 정리 작업 |
| 인증 | Firebase Authentication | 사용자별 인증과 토큰 갱신 |
| 데이터베이스 | Cloud Firestore | 기기·클립보드·수신 및 읽음 상태 저장 |
| 파일 저장소 | Cloud Storage | 최대 50MB 이미지·파일 저장 및 수명 주기 관리 |
| 알림 | Firebase Cloud Messaging | 수신 대상 기기에 data 메시지 전달 |
| 보안 | Firestore Rules, Storage Rules | 사용자 경로·기기 상태·파일 경로·크기·만료 시각 검증 |

### 구현 포인트

- **단일 최신 항목 정책**: 새 항목 생성 시 이전 Firestore 문서와 연결된 Storage 객체를 함께 정리해 계정별 최신 항목 1개만 유지합니다.
- **5분 보관 정책**: Android, macOS, Cloud Functions, Firestore Rules에 동일한 만료 기준을 적용했습니다.
- **전송 안정성**: 파일 전송은 업로드·문서 생성·기존 파일 삭제 순으로 처리하고, 실패 시 원인별 재시도 상태를 제공합니다.
- **기기 세션 보호**: 연결 해제한 기기는 `revokedDevices` 기록으로 재등록·heartbeat·전송을 차단하고, FCM으로 세션 종료를 알립니다.
- **보안 설정 분리**: macOS 값은 `Secrets.xcconfig` → `Info.plist` 빌드 주입 방식으로, Android Firebase 설정은 로컬 `google-services.json`으로 분리합니다.

## 참고

- 이 저장소에는 실제 Firebase 키나 개인 설정 파일을 포함하지 않습니다.
- Firebase 설정값·배포 대상은 개인 프로젝트 환경에 맞춰 설정해야 합니다.
