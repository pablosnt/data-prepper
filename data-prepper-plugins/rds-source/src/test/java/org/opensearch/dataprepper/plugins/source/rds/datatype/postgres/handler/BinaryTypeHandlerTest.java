package org.opensearch.dataprepper.plugins.source.rds.datatype.postgres.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.dataprepper.plugins.source.rds.datatype.postgres.PostgresDataType;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class BinaryTypeHandlerTest {
    private BinaryTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BinaryTypeHandler();
    }

    @Test
    public void test_handle_binary_string() {
        String columnName = "testColumn";
        PostgresDataType columnType = PostgresDataType.BYTEA;
        String value = "\\xDEADBEEF";
        Object result = handler.process(columnType, columnName, value);
        assertThat(result, is(instanceOf(String.class)));
        assertThat(result, is(value));
    }

    @Test
    public void test_handle_map_value_byte() {
        final PostgresDataType columnType = PostgresDataType.BYTEA;
        final String columnName = "test_column";
        final Map<String, Object> value = new HashMap<>();
        final String testData = "Text with a single quote: O'Reilly";
        final String expected = "\\x54657874207769746820612073696e676c652071756f74653a204f275265696c6c79";
        value.put("bytes", testData);

        final Object result = handler.handle(columnType, columnName, value);

        assertThat(result, is(instanceOf(String.class)));
        assertThat(result, is(expected));
    }
}
