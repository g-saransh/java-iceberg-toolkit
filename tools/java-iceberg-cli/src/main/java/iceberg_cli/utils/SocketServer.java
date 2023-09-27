/**
 * (c) Copyright IBM Corp. 2023. All Rights Reserved.
 */

package iceberg_cli.utils;

import iceberg_cli.IcebergApplication;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
// import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 
 * Creates a Unix domain socket server. For each connection,
 * a new thread is created to handle the client request. Response
 * from the IcebergApplication is then sent back to the client.
 *
 */
public class SocketServer {
    // private final String unixAddress = "/tmp/iceberg_service.server";
    private final int port = 1234;
    private final ServerSocketChannel serverChannel;
    private final ExecutorService pool;
    private final Integer minNumThreads = 10;
    
    /**
     * Create a serverChannel bound to the unixAddress path.
     * Create a pool of threads that can process multiple clients.
     * @throws IOException
     */
    public SocketServer() throws IOException {
        // UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(unixAddress);
        // serverChannel = ServerSocketChannel.open(StandardProtocolFamily.INET);
        serverChannel = ServerSocketChannel.open();
        
        // Delete the file if it already exists
        // Files.deleteIfExists(Path.of(unixAddress));
        // Bind to the socket Address
        serverChannel.bind(new InetSocketAddress(port));
        
        // Create a pool of fixed number of threads for handling the client connections
        String s_numThreads = System.getenv("ICEBERG_TOOLKIT_NUM_THREADS");
        int numThreads = (s_numThreads == null) ? minNumThreads : Integer.valueOf(s_numThreads);
        pool = Executors.newFixedThreadPool(numThreads < minNumThreads ? minNumThreads : numThreads);
    }
    
    /**
     * Create a new thread that will handle the incoming connection
     * @throws IOException
     */
    public void runServer() throws IOException {
        try {
            System.out.println(String.format("%s : Server listening for new connections",
                    new Timestamp(System.currentTimeMillis())));
            while (true) {
                pool.execute(new RequestHandler(serverChannel.accept()));
            }
        } catch (Exception exp) {
            // Clean up if the execution is interrupted
            stopServer();
        }
    }
    
    /**
     * Shutdown the ExecutorService and close the server channel 
     */
    public void stopServer() {
        System.out.println(String.format("%s : Shutting down server",
                new Timestamp(System.currentTimeMillis())));
        pool.shutdown();
        try {
            if (!pool.awaitTermination(120, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
            serverChannel.close();
        } catch (IOException ioExp) {
            ioExp.getStackTrace();
        } catch (InterruptedException intrExp) {
            pool.shutdownNow();
        }
        System.out.println(String.format("%s : Server stopped listening",
                new Timestamp(System.currentTimeMillis())));
    }
    
    /**
     * Handles incoming connections
     */
    private class RequestHandler implements Runnable {
        private SocketChannel channel;
        private static final int MAX_BUF_LEN = 16384;
        
        public RequestHandler(SocketChannel channel) {
            this.channel = channel;
        }
        
        public void run() {
            try {
                // Get the client request
                readSocketMessage(channel).ifPresent(message -> {
                    while(1) {
                        try {
                            String[] args = StringUtils.tokenizeQuotedString(message).toArray(new String[0]);
                            // Process client request
                            String response = new IcebergApplication().processRequest(args).trim();
                            // Send back response from the IcebergApplication to the client
                            sendMessage(channel, response, 0);
                            break;
                        } catch (Exception e) {
                            String retryScenario = "is not same as the current table metadata location";
                            if (!e.getMessage().contains(retryScenario)) {
                                try {
                                    // Send back error message to the Client
                                    sendMessage(channel, e.getMessage(), 1);
                                } catch (IOException ioExp) {
                                    System.err.println(ioExp.getMessage());
                                }
                                break;
                            }
                        }
                    }
                    return;
                });
            } catch (Exception e) {
                System.err.println(e.getMessage());
            } finally {
                try {
                    channel.close();
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            }
        }
        
        /**
         * Read message sent from the client
         * @param channel
         * @return
         * @throws IOException
         */
        private Optional<String> readSocketMessage(SocketChannel channel) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(MAX_BUF_LEN);
            int bytesRead = channel.read(buffer);
            if (bytesRead < 0)
                return Optional.empty();
            
            byte[] bytes = new byte[bytesRead];
            buffer.flip();
            buffer.get(bytes);
            String message = new String(bytes);
            
            return Optional.of(message);
        }
        
        /**
         * Send response back to the client
         * @param channel
         * @param message
         * @throws IOException
         */
        private void sendMessage(SocketChannel channel, String message, int errorFlag) throws IOException {
            // Create response body
            int size = message.length() + 2 * Integer.BYTES;
            ByteBuffer buffer = ByteBuffer.allocate(size);
            buffer.clear();
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            // Add error flag
            buffer.putInt(errorFlag);
            // Add size of the response
            buffer.putInt(message.length());
            // Add response
            buffer.put(message.getBytes());
            buffer.flip();
                        
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }
}

