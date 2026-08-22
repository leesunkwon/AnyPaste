import AppKit
import SwiftUI

enum PasteColors {
    static let brand = Color(red: 1.0, green: 0.40, blue: 0.0)
    static let action = Color(red: 0.16, green: 0.19, blue: 0.22)
    static let actionPressed = Color(red: 0.33, green: 0.36, blue: 0.43)
    static let brandForeground = Color(red: 0.73, green: 0.22, blue: 0.0)

    static let background = Color(nsColor: .windowBackgroundColor)
    static let surface = Color(nsColor: .controlBackgroundColor)
    static let surfaceRaised = Color(nsColor: .textBackgroundColor)
    static let surfaceMuted = Color(nsColor: .quaternarySystemFill)
    static let surfacePressed = Color(nsColor: .selectedControlColor).opacity(0.14)
    static let border = Color(nsColor: .separatorColor)
    static let borderStrong = Color(nsColor: .tertiaryLabelColor)

    static let text = Color(nsColor: .labelColor)
    static let textSecondary = Color(nsColor: .secondaryLabelColor)
    static let textTertiary = Color(nsColor: .tertiaryLabelColor)
    static let textDisabled = Color(nsColor: .disabledControlTextColor)

    static let success = Color(red: 0.0, green: 0.42, blue: 0.33)
    static let successSurface = Color(red: 0.93, green: 0.98, blue: 0.96)
    static let warning = Color(red: 0.42, green: 0.31, blue: 0.0)
    static let warningSurface = Color(red: 1.0, green: 0.97, blue: 0.87)
    static let danger = Color(red: 0.70, green: 0.15, blue: 0.12)
    static let dangerSurface = Color(red: 0.99, green: 0.94, blue: 0.94)
    static let informative = Color(red: 0.04, green: 0.34, blue: 0.82)
    static let informativeSurface = Color(red: 0.94, green: 0.97, blue: 1.0)
}

enum PasteSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 6
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
    static let xxxl: CGFloat = 32
    static let huge: CGFloat = 40
}

enum PasteRadius {
    static let small: CGFloat = 8
    static let medium: CGFloat = 12
    static let large: CGFloat = 16
    static let pill: CGFloat = 999
}

enum PasteTypography {
    static let screenTitle = Font.system(size: 28, weight: .bold, design: .rounded)
    static let pageTitle = Font.system(size: 22, weight: .bold, design: .rounded)
    static let sectionTitle = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 14, weight: .regular)
    static let bodyStrong = Font.system(size: 14, weight: .semibold)
    static let caption = Font.system(size: 12, weight: .regular)
    static let captionStrong = Font.system(size: 12, weight: .semibold)
}

enum PasteStatusTone {
    case neutral
    case informative
    case success
    case warning
    case danger

    var foreground: Color {
        switch self {
        case .neutral: PasteColors.textSecondary
        case .informative: PasteColors.informative
        case .success: PasteColors.success
        case .warning: PasteColors.warning
        case .danger: PasteColors.danger
        }
    }

    var background: Color {
        switch self {
        case .neutral: PasteColors.surfaceMuted
        case .informative: PasteColors.informativeSurface
        case .success: PasteColors.successSurface
        case .warning: PasteColors.warningSurface
        case .danger: PasteColors.dangerSurface
        }
    }
}

extension SyncStatus {
    var label: String {
        switch self {
        case .stopped:
            "동기화 중지"
        case .syncing:
            "동기화 중"
        case .upToDate:
            "최신 상태"
        case .offline:
            "오프라인"
        case .failed:
            "동기화 실패"
        }
    }
}

struct PasteCard<Content: View>: View {
    private let padding: CGFloat
    private let content: () -> Content

    init(
        padding: CGFloat = PasteSpacing.lg,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.padding = padding
        self.content = content
    }

    var body: some View {
        content()
            .padding(padding)
            .background(PasteColors.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous)
                    .stroke(PasteColors.border, lineWidth: 1)
            }
    }
}

struct PastePageHeader: View {
    let title: String
    let subtitle: String
    var symbol: String?

    var body: some View {
        HStack(alignment: .top, spacing: PasteSpacing.md) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(PasteColors.brandForeground)
                    .frame(width: 40, height: 40)
                    .background(PasteColors.brand.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                    .accessibilityHidden(true)
            }

            VStack(alignment: .leading, spacing: PasteSpacing.xs) {
                Text(title)
                    .font(PasteTypography.pageTitle)
                    .foregroundStyle(PasteColors.text)
                    .accessibilityAddTraits(.isHeader)
                Text(subtitle)
                    .font(PasteTypography.body)
                    .foregroundStyle(PasteColors.textSecondary)
            }
            Spacer(minLength: PasteSpacing.lg)
        }
    }
}

struct PasteSectionHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
            Text(title)
                .font(PasteTypography.sectionTitle)
                .foregroundStyle(PasteColors.text)
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle)
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
            }
        }
    }
}

struct PasteStatusBadge: View {
    let label: String
    let tone: PasteStatusTone
    var symbol: String?

    var body: some View {
        HStack(spacing: PasteSpacing.xs) {
            if let symbol {
                Image(systemName: symbol)
                    .accessibilityHidden(true)
            }
            Text(label)
                .lineLimit(1)
        }
        .font(PasteTypography.captionStrong)
        .foregroundStyle(tone.foreground)
        .padding(.horizontal, PasteSpacing.md)
        .frame(minHeight: 28)
        .background(tone.background)
        .clipShape(Capsule())
        .accessibilityElement(children: .combine)
    }
}

struct PastePrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PasteTypography.bodyStrong)
            .foregroundStyle(isEnabled ? Color.white : PasteColors.textDisabled)
            .padding(.horizontal, PasteSpacing.lg)
            .frame(minHeight: 40)
            .background(
                isEnabled
                    ? (configuration.isPressed ? PasteColors.actionPressed : PasteColors.action)
                    : PasteColors.surfaceMuted
            )
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct PasteSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PasteTypography.bodyStrong)
            .foregroundStyle(isEnabled ? PasteColors.brandForeground : PasteColors.textDisabled)
            .padding(.horizontal, PasteSpacing.lg)
            .frame(minHeight: 40)
            .background(configuration.isPressed ? PasteColors.surfacePressed : PasteColors.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous)
                    .stroke(isEnabled ? PasteColors.border : PasteColors.border.opacity(0.55), lineWidth: 1)
            }
    }
}

struct PasteDangerButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PasteTypography.bodyStrong)
            .foregroundStyle(isEnabled ? PasteColors.danger : PasteColors.textDisabled)
            .padding(.horizontal, PasteSpacing.lg)
            .frame(minHeight: 40)
            .background(configuration.isPressed ? PasteColors.danger.opacity(0.12) : PasteColors.dangerSurface)
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
    }
}

struct PasteIconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(PasteColors.textSecondary)
            .frame(width: 36, height: 36)
            .background(configuration.isPressed ? PasteColors.surfacePressed : .clear)
            .clipShape(Circle())
    }
}

struct PasteEmptyState: View {
    let symbol: String
    let title: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: PasteSpacing.md) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(PasteColors.textTertiary)
                .accessibilityHidden(true)
            Text(title)
                .font(PasteTypography.sectionTitle)
                .foregroundStyle(PasteColors.text)
            Text(message)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 360)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(PasteSecondaryButtonStyle())
            }
        }
        .frame(maxWidth: .infinity, minHeight: 220)
        .padding(PasteSpacing.xxl)
        .accessibilityElement(children: .combine)
    }
}

struct PasteLoadingState: View {
    var message = "불러오는 중입니다"

    var body: some View {
        VStack(spacing: PasteSpacing.md) {
            ProgressView()
                .controlSize(.large)
            Text(message)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }
}

struct PasteErrorBanner: View {
    let message: String
    var dismiss: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: PasteSpacing.md) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(PasteColors.danger)
                .accessibilityHidden(true)
            Text(message)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.danger)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let dismiss {
                Button(action: dismiss) {
                    Image(systemName: "xmark")
                }
                .buttonStyle(PasteIconButtonStyle())
                .accessibilityLabel("오류 닫기")
            }
        }
        .padding(PasteSpacing.md)
        .background(PasteColors.dangerSurface)
        .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous)
                .stroke(PasteColors.danger.opacity(0.28), lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("오류: \(message)")
    }
}

struct PasteMetricCard: View {
    let symbol: String
    let value: String
    let label: String
    var tone: PasteStatusTone = .neutral

    var body: some View {
        PasteCard {
            HStack(spacing: PasteSpacing.md) {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(tone.foreground)
                    .frame(width: 38, height: 38)
                    .background(tone.background)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                    Text(value)
                        .font(PasteTypography.sectionTitle)
                        .foregroundStyle(PasteColors.text)
                    Text(label)
                        .font(PasteTypography.caption)
                        .foregroundStyle(PasteColors.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label), \(value)")
    }
}

extension View {
    func pastePagePadding() -> some View {
        padding(.horizontal, PasteSpacing.xxl)
            .padding(.vertical, PasteSpacing.xl)
    }
}
