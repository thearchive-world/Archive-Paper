package archive.suite;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Single suite for the fork's archive.* tests. Sets the {@code TestSuite}
 * configuration parameter so upstream's AllFeaturesExtension adopts this
 * suite and runs its full bootstrap (registries + datapack + dummy server).
 * Deliberately does NOT set {@code failIfNoTests = false} (upstream does):
 * an empty selection here must fail the build loudly, not pass green.
 */
@Suite
@SuiteDisplayName("Archive fork unit tests")
@IncludeTags("AllFeatures")
@SelectPackages("archive")
@ConfigurationParameter(key = "TestSuite", value = "AllFeatures")
public class ArchiveTestSuite {
}
