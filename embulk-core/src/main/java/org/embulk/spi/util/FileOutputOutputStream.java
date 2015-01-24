package org.embulk.spi.util;

import java.io.OutputStream;
import org.embulk.spi.Buffer;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.FileOutput;

public class FileOutputOutputStream
        extends OutputStream
{
    private final FileOutput out;
    private final BufferAllocator allocator;
    private int pos;
    private Buffer buffer;

    public FileOutputOutputStream(FileOutput out, BufferAllocator allocator)
    {
        this.out = out;
        this.allocator = allocator;
        this.buffer = allocator.allocate();
    }

    public void nextFile()
    {
        out.nextFile();
    }

    @Override
    public void write(int b)
    {
        buffer.array()[buffer.offset() + pos] = (byte) b;
        pos++;
        if (pos >= buffer.capacity()) {
            flush();
        }
    }

    @Override
    public void write(byte[] b, int off, int len)
    {
        while (true) {
            int available = buffer.capacity() - pos;
            if (available < len) {
                buffer.setBytes(pos, b, off, available);
                pos += available;
                len -= available;
                off += available;
                flush();
            } else {
                buffer.setBytes(pos, b, off, len);
                pos += len;
                if (available <= len) {
                    flush();
                }
                break;
            }
        }
    }

    private boolean doFlush()
    {
        if (pos > 0) {
            buffer.limit(pos);
            out.add(buffer);
            buffer = Buffer.EMPTY;
            pos = 0;
            return true;
        }
        return false;
    }

    @Override
    public void flush()
    {
        if (doFlush()) {
            buffer = allocator.allocate();
        }
    }

    public void finish()
    {
        doFlush();
        out.finish();
    }

    @Override
    public void close()
    {
        out.close();
        buffer.release();
        buffer = Buffer.EMPTY;
        pos = 0;
    }
}
