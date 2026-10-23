package org.celanwang.mio.ops;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MaintenanceMainTest {
    @Test void missingExplicitAdminCredentialsNeverFallsBackToApplicationSettings() {
        assertThatThrownBy(()->MaintenanceMain.run(new String[]{"recover-restored-runtime"},Map.of("PG_URL","jdbc:postgresql://production/db"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedOrAmbiguousOptionsFailBeforeConnecting() {
        for(var args:new String[][]{{"unknown"},{"retry-memory-job"},{"recover-restored-runtime","--job","x"},{"recover-restored-runtime","--expected","a"},{"retry-memory-job","--job","x","--job","y"},{"recover-restored-runtime","--secret"}})
            assertThatThrownBy(()->MaintenanceMain.run(args,Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void helpDoesNotRequireAnyDatabase() throws Exception {assertThat(MaintenanceMain.run(new String[]{"--help"},Map.of())).isZero();}
}
