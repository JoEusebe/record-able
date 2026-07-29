package dev.recordable.theme;

// Stand-in for the platform-specific dev.recordable.theme.ThemeColors (its
// real implementation differs slightly per legacy/modern/sandwich). Only the
// factory signature that common-module call sites (RecordableConfig) use is
// modeled here; see stub-apis/build.gradle for why real dependencies aren't used.
//
// The parameter is typed Object rather than ThemePreset (which now lives in
// :common) to avoid a circular project dependency between :common and
// :stub-apis; call sites passing a ThemePreset still type-check fine.
public final class ThemeColors {

    private ThemeColors() {
    }

    public static ThemeColors forPreset(Object preset) {
        throw new UnsupportedOperationException("stub");
    }
}
