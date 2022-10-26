package ok.dht.test.ilin.servers;

import ok.dht.test.ilin.config.ExpandableHttpServerConfig;
import ok.dht.test.ilin.domain.HeadersUtils;
import ok.dht.test.ilin.domain.ReplicasInfo;
import ok.dht.test.ilin.replica.ReplicasHandler;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExpandableHttpServer extends HttpServer {
    private final ExecutorService executorService;
    private final ReplicasHandler replicasHandler;
    private final Logger logger = LoggerFactory.getLogger(ExpandableHttpServer.class);
    private final int nodesSize;

    public ExpandableHttpServer(
        ReplicasHandler replicasHandler,
        int nodesSize,
        ExpandableHttpServerConfig config,
        Object... routers
    ) throws IOException {
        super(config, routers);
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(config.queueCapacity);
        this.executorService = new ThreadPoolExecutor(
            config.workers,
            config.workers,
            0L,
            TimeUnit.MILLISECONDS,
            queue
        );
        this.replicasHandler = replicasHandler;
        this.nodesSize = nodesSize;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        switch (request.getMethod()) {
            case Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE -> session.sendResponse(new Response(
                Response.BAD_REQUEST,
                Response.EMPTY
            ));
            default -> session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String key = request.getParameter("id=");
        if (key == null || key.isEmpty()) {
            handleDefault(request, session);
            return;
        }

        String ack = request.getParameter("ack=");
        String from = request.getParameter("from=");

        ReplicasInfo replicasInfo;
        try {
            if (ack == null || from == null) {
                replicasInfo = new ReplicasInfo(nodesSize);
            } else {
                replicasInfo = new ReplicasInfo(Integer.parseInt(ack), Integer.parseInt(from));
            }
        } catch (NumberFormatException e) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (replicasInfo.ack() > replicasInfo.from() || replicasInfo.ack() == 0) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        String timestamp = request.getHeader(HeadersUtils.TIMESTAMP_HEADER);
        boolean isController = false;
        if (timestamp == null) {
            isController = true;
            request.addHeader(HeadersUtils.TIMESTAMP_HEADER + System.currentTimeMillis());
        }

        if (!isController) {
            try {
                executorService.execute(() -> {
                    try {
                        session.sendResponse(replicasHandler.selfExecute(key, request));
                    } catch (Exception e) {
                        logger.error("failed to send request: {}", e.getMessage());
                        sendBadRequest(session);
                    }
                });
            } catch (RejectedExecutionException e) {
                logger.error("Failed to run execution: {}", e.getMessage());
                sendServiceUnavailable(session);
            }
        } else {
            try {
                executorService.execute(() -> {
                    try {
                        session.sendResponse(replicasHandler.execute(key, replicasInfo, request));
                    } catch (IOException e) {
                        logger.error("failed to send response: {}", e.getMessage());
                    }
                });
            } catch (RejectedExecutionException e) {
                logger.error("Failed to run execution: {}", e.getMessage());
                sendServiceUnavailable(session);
            }
        }
    }

    private void sendServiceUnavailable(HttpSession session) {
        try {
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        } catch (IOException e) {
            logger.error("Failed to send response: {}", e.getMessage());
        }
    }

    private void sendBadRequest(HttpSession session) {
        try {
            session.sendError(Response.BAD_REQUEST, "failed to execute request.");
        } catch (IOException e) {
            logger.error("failed to send error: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        Arrays.stream(selectors).forEach(it -> {
            if (it.selector.isOpen()) {
                it.selector.forEach(Session::close);
            }
        });
        super.stop();
    }
}
