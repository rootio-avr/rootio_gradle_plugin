package io.root.patcher;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gradle {@link ValueSource} that resolves a patched dependency coordinate for a given GAV string,
 * using a local cache backed by {@link DepCache} and the Root.io API via {@link RootIoClient}.
 */
public abstract class RootIoValueSource implements ValueSource<String, RootIoValueSource.Parameters> {

    private static final AtomicReference<RootIoClient> clientRef = new AtomicReference<>();

    /** Input parameters for {@link RootIoValueSource}. */
    public interface Parameters extends ValueSourceParameters {
        /** @return Maven GAV string of the dependency to look up */
        Property<String> getCoords();
        /** @return Root.io API base URL */
        Property<String> getApiUrl();
        /** @return Root.io API key */
        Property<String> getApiKey();
        /** @return absolute path to the project root directory, used to locate the cache */
        Property<String> getRootDirPath();
        /** @return cache TTL in hours */
        Property<Long> getTtlHours();
        /** @return maximum number of retry attempts */
        Property<Integer> getMaxRetries();
        /** @return base delay in milliseconds for exponential backoff */
        Property<Long> getRetryBaseDelayMs();
        /** @return ignore entries as "group:artifact@version" strings sent to the API */
        ListProperty<String> getIgnore();
        /** @return true to read the aliased patch coordinate, false for the upstream-group one */
        Property<Boolean> getUseAlias();
    }

    @Override
    @Nullable
    public String obtain() {
        Parameters p = getParameters();
        List<String> ignoreEntries = p.getIgnore().getOrElse(List.of());
        boolean useAlias = p.getUseAlias().getOrElse(false);
        RootIoClient client = clientRef.updateAndGet(existing ->
            existing != null ? existing : new RootIoClient(p.getMaxRetries().get(), p.getRetryBaseDelayMs().get()));
        return DepCache.lookup(
            p.getCoords().get(),
            ignoreEntries,
            new File(p.getRootDirPath().get()),
            p.getTtlHours().get(),
            useAlias,
            () -> client.query(
                p.getCoords().get(),
                ignoreEntries,
                p.getApiUrl().get(),
                p.getApiKey().getOrNull(),
                useAlias
            )
        );
    }
}
