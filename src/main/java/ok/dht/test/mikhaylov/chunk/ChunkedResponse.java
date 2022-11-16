package ok.dht.test.mikhaylov.chunk;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.net.Socket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class ChunkedResponse extends Response {
    private static final int BUFFER_SIZE = 1024;

    private final ChunkedIterator iterator;

    public ChunkedResponse(Iterator<byte[]> iterator) {
        super(OK);
        this.iterator = new ChunkedIterator(iterator);
        super.addHeader("Transfer-Encoding: chunked");
    }

    public void writeBody(HttpSession session) throws IOException {
        while (iterator.hasNext()) {
            session.write(new ByteArrayQueueItem(iterator.next()));
        }
    }

    private static class ByteArrayQueueItem extends Session.QueueItem {
        private final byte[] data;

        public ByteArrayQueueItem(byte[] data) {
            this.data = data;
        }

        @Override
        public int write(Socket socket) throws IOException {
            return socket.write(data, 0, data.length);
        }
    }

    private static class ChunkedIterator implements Iterator<byte[]> {
        private final Iterator<byte[]> wrappedIterator;

        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

        private boolean hasNext = true;

        private static final byte[] CRLF = new byte[]{'\r', '\n'};

        private ChunkedIterator(Iterator<byte[]> iterator) {
            this.wrappedIterator = iterator;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public byte[] next() {
            while (true) {
                byte[] next;
                if (!wrappedIterator.hasNext()) {
                    hasNext = false;
                    next = new byte[]{};
                } else {
                    next = wrappedIterator.next();
                }
                byte[] length = Integer.toHexString(next.length).getBytes(StandardCharsets.UTF_8);
                boolean flush = (buffer.position() != 0 && buffer.remaining() < length.length + 2 + next.length + 2);
                byte[] result = null;
                if (flush) {
                    result = new byte[buffer.position()];
                    buffer.flip();
                    buffer.get(result);
                    buffer.clear();
                }
                buffer.put(length);
                buffer.put(CRLF);
                buffer.put(next);
                buffer.put(CRLF);
                if (flush) {
                    return result;
                }
            }
        }
    }
}
