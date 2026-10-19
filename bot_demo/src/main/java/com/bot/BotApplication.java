package com.bot;

import com.bot.api.RestApiServer;
import com.bot.bridge.PythonBridge;
import com.bot.config.BotConfig;
import com.bot.db.DatabaseManager;
import com.bot.model.TargetSite;
import com.bot.monitor.WebMonitorService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class BotApplication {

    public static void main(String[] args) {
        // Ensure UTF-8 console output where supported
        try {
            System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        printBanner();

        // 1. Load config
        BotConfig config = BotConfig.load();
        System.out.println("[1] Da tai cau hinh he thong:");
        System.out.println("    - Tan suat quet (interval) : " + config.getPollIntervalSeconds() + " giay");
        System.out.println("    - So luong site giam sat   : " + config.getTargetSites().size());

        // 2. Locate python crawler script
        File scriptFile = new File("python" + File.separator + "crawler.py");
        if (!scriptFile.exists()) {
            System.err.println("[LOI] Khong tim thay script python tai: " + scriptFile.getAbsolutePath());
            return;
        }

        // 3. Initialize Python Bridge with timeout and security settings
        PythonBridge pythonBridge = new PythonBridge(
                config.getPythonCommand(),
                scriptFile.getPath(),
                config.getProcessTimeoutSeconds(),
                config.isAllowLocalMock(),
                config.getAccuweatherApiKey(),
                config.getAccuweatherLocationKey()
        );
        System.out.println("[2] Khoi tao Python Bridge:");
        System.out.println("    - Python executable : " + pythonBridge.getPythonExecutable());
        System.out.println("    - Crawler script    : " + pythonBridge.getScriptPath());
        System.out.println("    - Timeout thuc thi  : " + pythonBridge.getTimeoutSeconds() + " giay");
        System.out.println("    - Che do Mock Local : " + (config.isAllowLocalMock() ? "BAT (allow 127.0.0.1)" : "TAT (chan SSRF)"));

        // 4. Initialize Server SQLite Database
        DatabaseManager databaseManager = new DatabaseManager(config.getDatabasePath());
        System.out.println("[3] Khoi tao SQLite Database Server tai: " + config.getDatabasePath());

        // 5. Initialize and start Web Monitor Service with Database Transaction support
        WebMonitorService monitorService = new WebMonitorService(
                pythonBridge,
                databaseManager,
                config.getTargetSites(),
                config.getPollIntervalSeconds()
        );

        // 6. Initialize and start Embedded REST API Server for Android Clients
        RestApiServer apiServer = new RestApiServer(
                config.getServerApiPort(),
                config.getApiAuthToken(),
                config.getCrawlCooldownSeconds(),
                databaseManager,
                monitorService
        );
        try {
            apiServer.start();
        } catch (Exception e) {
            System.err.println("\n" + "=".repeat(75));
            System.err.println(" [CANH BAO NGUY HIEM] KHONG THE KHOI DONG REST API SERVER TREN PORT " + config.getServerApiPort());
            System.err.println(" Chi tiet loi: " + e.getMessage());
            System.err.println("-".repeat(75));
            System.err.println(" HE QUA:");
            System.err.println("   - Ung dung Android se KHONG THE KET NOI duoc toi may chu.");
            System.err.println("   - Android Emulator se bao loi: 'fail to connect to /10.0.2.2 (port: " + config.getServerApiPort() + ")'");
            System.err.println(" NGUYEN NHAN:");
            System.err.println("   1. Cong " + config.getServerApiPort() + " dang bi chiem boi tien trinh khac (XAMPP, Apache, Tomcat,");
            System.err.println("      hoac mot tien trinh Java/Bot cu chua tat).");
            System.err.println("   2. Python Mock Server cu dang chay tren cong " + config.getServerApiPort() + " (luu y: mock server hien da doi sang 8085).");
            System.err.println(" CACH KHAC PHUC:");
            System.err.println("   - Kiem tra port tren Windows: netstat -ano | findstr :" + config.getServerApiPort());
            System.err.println("   - Hoac doi port khac trong 'config.properties' (vi du: server.api.port=8088),");
            System.err.println("     sau do cap nhat Server URL tuong ung tren ung dung Android.");
            System.err.println("=".repeat(75) + "\n");
        }

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[DANG DUNG] Dang dung Bot va don dep tien trinh...");
            apiServer.stop();
            monitorService.stop();
        }));

        monitorService.start();

        printHelp();

        // 5. Interactive command loop in main thread
        Scanner scanner = new Scanner(System.in);
        while (monitorService.isRunning()) {
            try {
                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                switch (line.toLowerCase()) {
                    case "check":
                        System.out.println(">>> [THUC THI] Kiem tra ngay lap tuc tat ca cac trang...");
                        monitorService.checkAllSites();
                        break;

                    case "list":
                        System.out.println("\n--- DANH SACH TRANG DANG GIAM SAT ---");
                        for (TargetSite s : monitorService.getSites()) {
                            System.out.println(" - " + s);
                        }
                        System.out.println("-------------------------------------\n");
                        break;

                    case "help":
                        printHelp();
                        break;

                    case "exit":
                    case "quit":
                        System.out.println(">>> [THOAT] Dang tat Bot...");
                        apiServer.stop();
                        monitorService.stop();
                        System.exit(0);
                        return;

                    default:
                        System.out.println("Lenh khong hop le: '" + line + "'. Go 'help' de xem danh sach lenh.");
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    private static void printBanner() {
        System.out.println("==========================================================================");
        System.out.println("    JAVA + PYTHON EMBEDDED CRAWLER & HTML UPDATE MONITOR BOT            ");
        System.out.println("       Theo doi thoi tiet & gia xang dau tu link tinh hang ngay           ");
        System.out.println("==========================================================================");
    }

    private static void printHelp() {
        System.out.println("\n=== CAC LENH HO TRO TREN TERMINAL ===");
        System.out.println("  check : Quet kiem tra ngay lap tuc");
        System.out.println("  list  : Xem danh sach cac trang va ma hash hien tai");
        System.out.println("  help  : Hien thi huong dan nay");
        System.out.println("  exit  : Thoat chuong trinh bot");
        System.out.println("=====================================\n");
    }
}
