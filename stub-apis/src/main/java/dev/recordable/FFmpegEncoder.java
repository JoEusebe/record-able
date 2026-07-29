package dev.recordable;

// Stand-in for the platform-specific dev.recordable.FFmpegEncoder (legacy /
// modern / sandwich each ship their own, built against that variant's mapped
// Minecraft classes). Only the FfmpegStatus shape used by the common-module
// call sites (VideoMetadata, PlatformUtils) is modeled here; see
// stub-apis/build.gradle for why real dependencies aren't used.
public final class FFmpegEncoder {

    private FFmpegEncoder() {
    }

    public static FfmpegStatus detectFfmpeg() {
        throw new UnsupportedOperationException("stub");
    }

    public interface FfmpegStatus {
        boolean found();

        String executable();
    }
}
