import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import jakarta.annotation.Nullable;
import lombok.Data;
import lombok.EqualsAndHashCode;


public class PluginHubManifest
{
    public static final Base64.Encoder HASH_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Data
    public static class JarData
    {
        public String internalName;
        public String displayName;
        public String jarHash;
        public int jarSize;
    }

    @Data
    public static class ManifestLite
    {
        public ArrayList<JarData> jars = new ArrayList<>();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ManifestFull extends ManifestLite
    {
        public ArrayList<DisplayData> display = new ArrayList<>();
    }

    @Data
    public static class DisplayData
    {
        public String internalName;
        public String displayName;
        public String version;

        @Nullable
        public String iconHash;

        public long createdAt;
        public long lastUpdatedAt;

        public String author;

        @Nullable
        public String description;

        @Nullable
        public String warning;

        @Nullable
        public String[] tags;

        @Nullable
        public Long buildFailAt;

        @Nullable
        public String unavailableReason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Stub extends DisplayData
    {
        public String[] plugins;
    }
}