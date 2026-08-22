import SwiftUI

struct ConfigurationMissingView: View {
    var missingValues: [String] = []
    var retry: (() -> Void)?

    var body: some View {
        ZStack {
            PasteColors.background
                .ignoresSafeArea()

            PasteCard(padding: PasteSpacing.xxxl) {
                VStack(alignment: .leading, spacing: PasteSpacing.xxl) {
                    HStack(alignment: .top, spacing: PasteSpacing.lg) {
                        Image(systemName: "wrench.and.screwdriver.fill")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(PasteColors.warning)
                            .frame(width: 48, height: 48)
                            .background(PasteColors.warningSurface)
                            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: PasteSpacing.xs) {
                            Text("앱 구성이 필요합니다")
                                .font(PasteTypography.screenTitle)
                                .foregroundStyle(PasteColors.text)
                                .accessibilityAddTraits(.isHeader)
                            Text("동기화를 시작하기 전에 서비스 연결 정보를 설정해 주세요.")
                                .font(PasteTypography.body)
                                .foregroundStyle(PasteColors.textSecondary)
                        }
                    }

                    VStack(alignment: .leading, spacing: PasteSpacing.md) {
                        instructionRow(number: 1, text: "프로젝트의 예제 설정 파일을 복사해 로컬 설정 파일을 만드세요.")
                        instructionRow(number: 2, text: "필요한 연결 값을 입력한 뒤 앱을 다시 실행하세요.")
                        instructionRow(number: 3, text: "로컬 설정 파일은 원격 저장소에 올리지 마세요.")
                    }

                    if !missingValues.isEmpty {
                        VStack(alignment: .leading, spacing: PasteSpacing.sm) {
                            Text("확인할 항목")
                                .font(PasteTypography.captionStrong)
                                .foregroundStyle(PasteColors.textSecondary)

                            ForEach(missingValues, id: \.self) { value in
                                Label(value, systemImage: "exclamationmark.circle")
                                    .font(PasteTypography.body)
                                    .foregroundStyle(PasteColors.text)
                            }
                        }
                        .padding(PasteSpacing.lg)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(PasteColors.surfaceMuted)
                        .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                    }

                    if let retry {
                        Button("다시 확인", action: retry)
                            .buttonStyle(PastePrimaryButtonStyle())
                            .keyboardShortcut(.defaultAction)
                            .accessibilityHint("설정 상태를 다시 확인합니다")
                    }
                }
            }
            .frame(maxWidth: 600)
            .padding(PasteSpacing.xxxl)
        }
        .frame(minWidth: 560, minHeight: 500)
    }

    private func instructionRow(number: Int, text: String) -> some View {
        HStack(alignment: .top, spacing: PasteSpacing.md) {
            Text("\(number)")
                .font(PasteTypography.captionStrong)
                .foregroundStyle(PasteColors.brandForeground)
                .frame(width: 24, height: 24)
                .background(PasteColors.brand.opacity(0.12))
                .clipShape(Circle())
                .accessibilityHidden(true)
            Text(text)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(number)단계, \(text)")
    }
}

struct AuthenticationView: View {
    @ObservedObject var model: AppModel

    @State private var mode: AuthenticationMode = .signIn
    @State private var email = ""
    @State private var name = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var resetWasSent = false
    @FocusState private var focusedField: AuthenticationField?

    var body: some View {
        ZStack {
            PasteColors.background
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: PasteSpacing.xxl) {
                    brandHeader

                    PasteCard(padding: PasteSpacing.xxl) {
                        VStack(alignment: .leading, spacing: PasteSpacing.xl) {
                            authenticationHeader

                            if let errorMessage = model.errorMessage {
                                PasteErrorBanner(message: errorMessage) {
                                    model.dismissError()
                                }
                            }

                            if mode == .resetPassword, resetWasSent {
                                resetSuccessMessage
                            } else {
                                formFields
                                primaryAction
                            }

                            modeActions
                        }
                    }
                    .frame(maxWidth: 460)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, PasteSpacing.xxxl)
                .padding(.vertical, PasteSpacing.huge)
            }
        }
        .frame(minWidth: 560, minHeight: 620)
        .onChange(of: mode) {
            resetWasSent = false
            password = ""
            confirmation = ""
            focusedField = .email
        }
    }

    private var brandHeader: some View {
        VStack(spacing: PasteSpacing.md) {
            Image(systemName: "doc.on.clipboard.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(width: 64, height: 64)
                .background(PasteColors.brand)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous))
                .accessibilityHidden(true)

            Text("AnyPaste")
                .font(PasteTypography.screenTitle)
                .foregroundStyle(PasteColors.text)
                .accessibilityAddTraits(.isHeader)
            Text("기기 사이에서 필요한 내용을 빠르게 이어 쓰세요.")
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.textSecondary)
                .multilineTextAlignment(.center)
        }
    }

    private var authenticationHeader: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.xs) {
            Text(mode.title)
                .font(PasteTypography.pageTitle)
                .foregroundStyle(PasteColors.text)
                .accessibilityAddTraits(.isHeader)
            Text(mode.subtitle)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private var formFields: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.lg) {
            formField(label: "이메일", message: emailValidationMessage) {
                TextField("name@example.com", text: $email)
                    .textFieldStyle(.roundedBorder)
                    .controlSize(.large)
                    .focused($focusedField, equals: .email)
                    .onSubmit(submitFromKeyboard)
                    .accessibilityLabel("이메일")
            }

            if mode == .signUp {
                formField(label: "이름", message: nameValidationMessage) {
                    TextField("표시할 이름", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.large)
                        .focused($focusedField, equals: .name)
                        .onSubmit { focusedField = .password }
                        .accessibilityLabel("이름")
                }
            }

            if mode != .resetPassword {
                formField(label: "비밀번호", message: passwordValidationMessage) {
                    SecureField(mode == .signUp ? "8자 이상 입력" : "비밀번호", text: $password)
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.large)
                        .focused($focusedField, equals: .password)
                        .onSubmit(submitFromKeyboard)
                        .accessibilityLabel("비밀번호")
                }
            }

            if mode == .signUp {
                formField(label: "비밀번호 확인", message: confirmationValidationMessage) {
                    SecureField("비밀번호 다시 입력", text: $confirmation)
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.large)
                        .focused($focusedField, equals: .confirmation)
                        .onSubmit(submitFromKeyboard)
                        .accessibilityLabel("비밀번호 확인")
                }
            }
        }
    }

    private func formField<Field: View>(
        label: String,
        message: String?,
        @ViewBuilder field: () -> Field
    ) -> some View {
        VStack(alignment: .leading, spacing: PasteSpacing.xs) {
            Text(label)
                .font(PasteTypography.captionStrong)
                .foregroundStyle(PasteColors.text)
            field()
            if let message {
                Text(message)
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.danger)
                    .accessibilityLabel("\(label) 오류: \(message)")
            }
        }
    }

    private var primaryAction: some View {
        Button(action: performPrimaryAction) {
            HStack(spacing: PasteSpacing.sm) {
                if model.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                        .accessibilityHidden(true)
                }
                Text(model.isLoading ? mode.loadingTitle : mode.actionTitle)
                    .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(PastePrimaryButtonStyle())
        .disabled(!isFormValid || model.isLoading)
        .keyboardShortcut(.defaultAction)
        .accessibilityHint(mode.accessibilityHint)
    }

    @ViewBuilder
    private var modeActions: some View {
        switch mode {
        case .signIn:
            HStack {
                Button("비밀번호를 잊으셨나요?") {
                    mode = .resetPassword
                }
                .buttonStyle(.link)

                Spacer()

                Button("회원가입") {
                    mode = .signUp
                }
                .buttonStyle(.link)
            }
        case .signUp:
            HStack(spacing: PasteSpacing.xs) {
                Text("이미 계정이 있나요?")
                    .foregroundStyle(PasteColors.textSecondary)
                Button("로그인") {
                    mode = .signIn
                }
                .buttonStyle(.link)
            }
            .frame(maxWidth: .infinity, alignment: .center)
        case .resetPassword:
            Button("로그인으로 돌아가기") {
                mode = .signIn
            }
            .buttonStyle(.link)
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }

    private var resetSuccessMessage: some View {
        VStack(spacing: PasteSpacing.lg) {
            Image(systemName: "envelope.badge.fill")
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(PasteColors.success)
                .accessibilityHidden(true)
            Text("재설정 메일을 보냈습니다")
                .font(PasteTypography.sectionTitle)
                .foregroundStyle(PasteColors.text)
            Text("\(email)에 도착한 안내를 따라 비밀번호를 변경해 주세요. 메일이 보이지 않으면 스팸함도 확인해 주세요.")
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button("로그인으로 돌아가기") {
                mode = .signIn
            }
            .buttonStyle(PastePrimaryButtonStyle())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, PasteSpacing.lg)
        .accessibilityElement(children: .combine)
    }

    private var isEmailValid: Bool {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let atIndex = trimmed.firstIndex(of: "@") else { return false }
        return atIndex != trimmed.startIndex && trimmed[trimmed.index(after: atIndex)...].contains(".")
    }

    private var isFormValid: Bool {
        switch mode {
        case .signIn:
            return isEmailValid && !password.isEmpty
        case .signUp:
            return isEmailValid
                && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && password.count >= 8
                && password == confirmation
        case .resetPassword:
            return isEmailValid
        }
    }

    private var emailValidationMessage: String? {
        guard !email.isEmpty, !isEmailValid else { return nil }
        return "올바른 이메일 주소를 입력해 주세요."
    }

    private var nameValidationMessage: String? {
        guard mode == .signUp, !name.isEmpty,
              name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return "이름을 입력해 주세요."
    }

    private var passwordValidationMessage: String? {
        guard mode == .signUp, !password.isEmpty, password.count < 8 else { return nil }
        return "비밀번호는 8자 이상이어야 합니다."
    }

    private var confirmationValidationMessage: String? {
        guard mode == .signUp, !confirmation.isEmpty, password != confirmation else { return nil }
        return "비밀번호가 일치하지 않습니다."
    }

    private func submitFromKeyboard() {
        if mode == .signIn || mode == .resetPassword {
            performPrimaryAction()
        } else if focusedField == .email {
            focusedField = .name
        } else if focusedField == .password {
            focusedField = .confirmation
        } else {
            performPrimaryAction()
        }
    }

    private func performPrimaryAction() {
        guard isFormValid, !model.isLoading else { return }
        focusedField = nil

        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        Task {
            switch mode {
            case .signIn:
                await model.signIn(email: trimmedEmail, password: password)
            case .signUp:
                await model.signUp(
                    email: trimmedEmail,
                    name: trimmedName,
                    password: password,
                    confirmation: confirmation
                )
            case .resetPassword:
                await model.sendPasswordReset(email: trimmedEmail)
                if model.errorMessage == nil {
                    resetWasSent = true
                }
            }
        }
    }
}

private enum AuthenticationMode: Equatable {
    case signIn
    case signUp
    case resetPassword

    var title: String {
        switch self {
        case .signIn: "로그인"
        case .signUp: "회원가입"
        case .resetPassword: "비밀번호 재설정"
        }
    }

    var subtitle: String {
        switch self {
        case .signIn: "계정에 로그인하면 클립보드 기록을 모든 기기에서 확인할 수 있습니다."
        case .signUp: "새 계정을 만들고 기기 간 동기화를 시작하세요."
        case .resetPassword: "가입한 이메일로 비밀번호 변경 안내를 보내드립니다."
        }
    }

    var actionTitle: String {
        switch self {
        case .signIn: "로그인"
        case .signUp: "계정 만들기"
        case .resetPassword: "재설정 메일 보내기"
        }
    }

    var loadingTitle: String {
        switch self {
        case .signIn: "로그인 중"
        case .signUp: "계정 만드는 중"
        case .resetPassword: "메일 보내는 중"
        }
    }

    var accessibilityHint: String {
        switch self {
        case .signIn: "입력한 계정으로 로그인합니다"
        case .signUp: "입력한 정보로 새 계정을 만듭니다"
        case .resetPassword: "입력한 이메일로 재설정 안내를 보냅니다"
        }
    }
}

private enum AuthenticationField: Hashable {
    case email
    case name
    case password
    case confirmation
}
