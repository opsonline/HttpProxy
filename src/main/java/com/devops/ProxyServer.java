package com.devops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ProxyServer extends Thread {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);
    private final ServerSocket server;
    private Integer port;
    private Integer defaultPort = 1080;

    public ProxyServer() throws IOException {
        server = new ServerSocket(defaultPort);
        logger.info("代理服务运行监听地址: " + server.getInetAddress().getHostAddress() + ":" + defaultPort);
    }

    public ProxyServer(Integer port) throws IOException {
        this.port = port == null ? defaultPort : port;
        server = new ServerSocket(port);
        logger.info("代理服务运行监听地址: " + server.getInetAddress().getHostAddress() + ":" + port);
    }

    @Override
    public void run() {
        // 线程运行函数
        while (true) {
            try {
                Socket client = server.accept();
                logger.debug("客户端连接成功: " + client.getInetAddress().getHostAddress());
                new HttpConnectThread(client).start();
            } catch (Exception e) {
                logger.error("处理客户端连接失败: " + e.getMessage());
            }
        }
    }

    private static class HttpConnectThread extends Thread {
        private byte[] buffer = new byte[1024 * 1024 * 4];
        private int clientReadLength;
        private Socket client;
        private Socket remoteServer;
        private String requestLine;
        private DataInputStream clientInputStream;
        private DataOutputStream clientOutputStream;
        private DataInputStream remoteServerInputStream;
        private DataOutputStream remoteServerOutputStream;

        private String[] httpMethod = {"GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE", "CONNECT"};

        public HttpConnectThread(Socket client) {
            this.client = client;
        }

        private void connectRemoteServer(String host, Integer port) throws ConnectException {
            try {
                remoteServer = new Socket(host, port);
                remoteServerInputStream = new DataInputStream(remoteServer.getInputStream());
                remoteServerOutputStream = new DataOutputStream(remoteServer.getOutputStream());
            } catch (Exception e) {
                throw new ConnectException("连接目标地址失败，目标地址: " + host + ":" + port);
            }
        }

        @Override
        public void run() {
            try {
                clientInputStream = new DataInputStream(client.getInputStream());
                clientOutputStream = new DataOutputStream(client.getOutputStream());
                clientReadLength = clientInputStream.read(buffer, 0, buffer.length);

                if (clientReadLength == -1) {
                    return;
                }

                String clientInputString = new String(buffer, 0, clientReadLength);
                if (clientInputString.contains("\n")) {
                    requestLine = clientInputString.substring(0, clientInputString.indexOf("\n"));
                }

                if (requestLine == null) {
                    return;
                }

                String targHost = "";
                Integer targPort = 80;
                if (requestLine.contains("CONNECT ")) {
                    String[] requestLineArr = requestLine.split(" ");
                    targHost = requestLineArr[1].split(":")[0];
                    targPort = Integer.valueOf(requestLineArr[1].split(":")[1]);
                }

                if (requestLine.contains("http://") && requestLine.contains("HTTP/")) {
                    // 从所读数据中取域名和端口号
                    Pattern pattern = Pattern.compile("http://([^/]+)/");
                    Matcher matcher = pattern.matcher(requestLine + "/");
                    if (matcher.find()) {
                        targHost = matcher.group(1);
                        if (targHost.contains(":")) {
                            targPort = Integer.parseInt(targHost.substring(targHost.indexOf(":") + 1));
                            targHost = targHost.substring(0, targHost.indexOf(":"));
                        }
                    }
                }

                if (targHost.equals("")) {
                    return;
                }
                logger.info(client.getInetAddress().getHostAddress() + " " + requestLine);
                connectRemoteServer(targHost, targPort);
                String method = requestLine.split(" ")[0];
                for (int i = 0; i < httpMethod.length; i++) {
                    if (method.equals(httpMethod[i])) {
                        requestHandler(httpMethod[i]);
                        break;
                    }
                }
            } catch (Exception e) {
                logger.error("HttpConnectThread error: " + e.getMessage());
                try {
                    if (client != null) {
                        client.close();
                    }
                } catch (Exception e1) {
                    logger.error("关闭客户端socket失败: " + client.getInetAddress().getHostAddress() + requestLine + "关闭失败:" + e1.getMessage());
                }
                try {
                    if (remoteServer != null) {
                        remoteServer.close();
                    }
                } catch (Exception e1) {
                    logger.error("关闭远端服务器socket失败: " + client.getInetAddress().getHostAddress() + requestLine + "关闭失败:" + e1.getMessage());
                }
            }
        }

        private void requestHandler(String method) throws IOException {
            if (method.equals("CONNECT")) {
                String ack = "HTTP/1.0 200 Connection established\r\n";
                ack = ack + "Proxy-agent: devops-proxy\r\n\r\n";
                clientOutputStream.write(ack.getBytes());
                clientOutputStream.flush();
                new HttpChannel(remoteServerInputStream, clientOutputStream).start();
                new HttpChannel(clientInputStream, remoteServerOutputStream).start();
            } else {
                remoteServerOutputStream.write(buffer, 0, clientReadLength);
                remoteServerOutputStream.flush();
                new HttpChannel(remoteServerInputStream, clientOutputStream).start();
                new HttpChannel(clientInputStream, remoteServerOutputStream).start();
            }
        }

        private static class HttpChannel extends Thread {
            private int bufferszie = 2048;
            private DataInputStream in;
            private DataOutputStream out;

            public HttpChannel(DataInputStream in, DataOutputStream out) {
                this.in = in;
                this.out = out;
            }

            private void close() {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException e1) {
                    logger.error("HttpChannel colse inputstream error: " + e1.getMessage());
                }

                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (IOException e1) {
                    logger.error("HttpChannel colse outputstream error: " + e1.getMessage());
                }
            }

            @Override
            public void run() {
                int len;
                byte[] buf = new byte[bufferszie];
                try {
                    while ((len = in.read(buf, 0, buf.length)) != -1) {
                        {
                            out.write(buf, 0, len);
                            out.flush();
                        }
                    }
                } catch (Exception e) {
//                    logger.error("HttpChannel error:" + e.getMessage());
                } finally {
                    close();
                }
            }
        }
    }
}
