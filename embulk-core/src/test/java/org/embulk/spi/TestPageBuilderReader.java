package org.embulk.spi;

import static org.embulk.spi.PageTestUtils.newColumn;
import static org.embulk.spi.PageTestUtils.newSchema;
import static org.embulk.spi.type.Types.BOOLEAN;
import static org.embulk.spi.type.Types.DOUBLE;
import static org.embulk.spi.type.Types.LONG;
import static org.embulk.spi.type.Types.STRING;
import static org.embulk.spi.type.Types.TIMESTAMP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.Schema;
import org.embulk.EmbulkTestRuntime;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TestPageBuilderReader
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    public static class MockPageOutput implements PageOutput
    {
        public List<Page> pages;

        public MockPageOutput()
        {
            this.pages = new ArrayList<>();
        }

        @Override
        public void add(Page page)
        {
            pages.add(page);
        }

        @Override
        public void finish()
        {
        }

        @Override
        public void close()
        {
        }
    }

    private BufferAllocator bufferAllocator;
    private PageReader reader;
    private PageBuilder builder;

    @Before
    public void setup()
    {
        this.bufferAllocator = runtime.getBufferAllocator();
    }

    @After
    public void destroy()
    {
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (builder != null) {
            builder.close();
            builder = null;
        }
    }

    @Test
    public void testBoolean()
    {
        check(newSchema(newColumn("col1", BOOLEAN)), false, true, true);
    }

    @Test
    public void testLong()
    {
        check(newSchema(newColumn("col1", LONG)), 1L, Long.MIN_VALUE,
                Long.MAX_VALUE);
    }

    @Test
    public void testDouble()
    {
        check(newSchema(newColumn("col1", DOUBLE)), 8.1, 3.141592, 4.3);
    }

    @Test
    public void testUniqueStrings()
    {
        check(newSchema(newColumn("col1", STRING)), "test1", "test2", "test0");
    }

    @Test
    public void testDuplicateStrings()
    {
        check(newSchema(newColumn("col1", STRING)), "test1", "test1", "test1");
    }

    @Test
    public void testDuplicateStringsMultiColumns()
    {
        check(newSchema(newColumn("col1", STRING), newColumn("col1", STRING)),
                "test2", "test1", "test1", "test2", "test2", "test0", "test1",
                "test1");
    }

    @Test
    public void testTimestamp()
    {
        check(newSchema(newColumn("col1", TIMESTAMP)),
                Timestamp.ofEpochMilli(0), Timestamp.ofEpochMilli(10));
    }

    @Test
    public void testNull()
    {
        check(newSchema(newColumn("col3", DOUBLE), newColumn("col1", STRING),
                newColumn("col3", LONG), newColumn("col3", BOOLEAN),
                newColumn("col2", TIMESTAMP)), null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    public void testMixedTypes()
    {
        check(newSchema(newColumn("col3", DOUBLE), newColumn("col1", STRING),
                newColumn("col3", LONG), newColumn("col3", BOOLEAN),
                newColumn("col2", TIMESTAMP)), 8122.0, "val1", 3L, false,
                Timestamp.ofEpochMilli(0), 140.15, "val2", Long.MAX_VALUE,
                true, Timestamp.ofEpochMilli(10));
    }

    private void check(Schema schema, Object... objects)
    {
        Page page = buildPage(schema, objects);
        checkPage(schema, page, objects);
    }

    private Page buildPage(Schema schema, final Object... objects)
    {
        List<Page> pages = buildPages(schema, objects);
        assertEquals(1, pages.size());
        return pages.get(0);
    }

    private List<Page> buildPages(Schema schema, final Object... objects)
    {
        MockPageOutput output = new MockPageOutput();
        this.builder = new PageBuilder(bufferAllocator, schema, output);
        int idx = 0;
        while (idx < objects.length) {
            for (int column = 0; column < builder.getSchema().getColumnCount(); ++column) {
                Object value = objects[idx++];
                if (value == null) {
                    builder.setNull(column);
                } else if (value instanceof Boolean) {
                    builder.setBoolean(column, (Boolean) value);
                } else if (value instanceof Double) {
                    builder.setDouble(column, (Double) value);
                } else if (value instanceof Long) {
                    builder.setLong(column, (Long) value);
                } else if (value instanceof String) {
                    builder.setString(column, (String) value);
                } else if (value instanceof Timestamp) {
                    builder.setTimestamp(column, (Timestamp) value);
                } else {
                    throw new IllegalStateException(
                            "Unsupported type in test utils: "
                                    + value.toString());
                }
            }
            builder.addRecord();
        }
        builder.flush();
        builder.close();
        return output.pages;
    }

    private void checkPage(Schema schema, Page page, final Object... objects)
    {
        this.reader = new PageReader(schema);
        reader.setPage(page);
        int idx = 0;
        while (idx < objects.length && reader.nextRecord()) {
            for (int column = 0; column < reader.getSchema().getColumnCount(); ++column) {
                Object value = objects[idx++];
                if (value == null) {
                    assertEquals(true, reader.isNull(column));
                } else if (value instanceof Boolean) {
                    assertEquals(value, reader.getBoolean(column));
                } else if (value instanceof Double) {
                    assertEquals(value, reader.getDouble(column));
                } else if (value instanceof Long) {
                    assertEquals(value, reader.getLong(column));
                } else if (value instanceof String) {
                    assertEquals(value, reader.getString(column));
                } else if (value instanceof Timestamp) {
                    assertEquals(value, reader.getTimestamp(column));
                } else {
                    throw new IllegalStateException(
                            "Unsupported type in test utils: "
                                    + value.toString());
                }
            }
        }
    }

    @Test
    public void testEmptySchema()
    {
        MockPageOutput output = new MockPageOutput();
        this.builder = new PageBuilder(bufferAllocator, newSchema(), output);
        builder.addRecord();
        builder.addRecord();
        builder.flush();
        builder.close();
        this.reader = new PageReader(newSchema());
        assertEquals(1, output.pages.size());
        reader.setPage(output.pages.get(0));
        assertTrue(reader.nextRecord());
        assertTrue(reader.nextRecord());
        assertFalse(reader.nextRecord());
    }

    @Test
    public void testRenewPage()
    {
        this.bufferAllocator = new BufferAllocator()
        {
            @Override
            public Buffer allocate()
            {
                return Buffer.allocate(1);
            }

            @Override
            public Buffer allocate(int minimumCapacity)
            {
                return Buffer.allocate(minimumCapacity);
            }
        };
        assertEquals(
                9,
                buildPages(newSchema(newColumn("col1", LONG)), 0L, 1L, 2L, 3L,
                        4L, 5L, 6L, 7L, 8L).size());
    }

    @Test
    public void testRenewPageWithStrings()
    {
        this.bufferAllocator = new BufferAllocator()
        {
            @Override
            public Buffer allocate()
            {
                return Buffer.allocate(1);
            }

            @Override
            public Buffer allocate(int minimumCapacity)
            {
                return Buffer.allocate(minimumCapacity);
            }
        };
        assertEquals(
                3,
                buildPages(
                        newSchema(newColumn("col1", LONG),
                                newColumn("col1", STRING)), 0L, "record0", 1L,
                        "record1", 3L, "record3").size());
    }

    @Test
    public void testRepeatableClose()
    {
        MockPageOutput output = new MockPageOutput();
        this.builder = new PageBuilder(bufferAllocator, newSchema(newColumn(
                "col1", STRING)), output);
        builder.close();
        builder.close();
    }

    @Test
    public void testRepeatableFlush()
    {
        MockPageOutput output = new MockPageOutput();
        this.builder = new PageBuilder(bufferAllocator, newSchema(newColumn(
                "col1", STRING)), output);
        builder.flush();
        builder.flush();
    }
}
