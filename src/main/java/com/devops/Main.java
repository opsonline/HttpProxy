package com.devops;

import java.io.IOException;
import java.util.logging.Logger;

public class Main {
    private static Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws IOException {
            int port = 1080;
            if (args != null && args.length > 0 && args[0].matches("\\d+")) {
                port = Integer.parseInt(args[0]);
            }
            new ProxyServer(port).start();
    }
}