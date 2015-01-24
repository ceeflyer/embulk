package org.embulk.spi;

public interface SchemaVisitor
{
    public void booleanColumn(Column column);

    public void longColumn(Column column);

    public void doubleColumn(Column column);

    public void stringColumn(Column column);

    public void timestampColumn(Column column);
}
