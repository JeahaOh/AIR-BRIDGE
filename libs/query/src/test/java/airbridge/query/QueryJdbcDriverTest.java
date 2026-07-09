package airbridge.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class QueryJdbcDriverTest {
    @Test
    void db2DriverIsBundled() {
        assertDoesNotThrow(() -> Class.forName("com.ibm.db2.jcc.DB2Driver"));
    }
}
