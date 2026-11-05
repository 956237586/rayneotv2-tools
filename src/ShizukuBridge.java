package com.rayneo.diag;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 借到 shell 权限（uid 2000），用来读 App 自身读不到的东西：
 * /proc/stat、/proc/loadavg、getprop ro.serialno、ps -A。
 * 每次设备重启后 Shizuku 服务需要重新用 adb 启动一次（非 root 的固有代价）。
 */
public final class ShizukuBridge {

    private ShizukuBridge() {
    }

    /** Shizuku 服务是否在跑（binder 是否可达）。 */
    public static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务版本号，-1 表示没有服务。 */
    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 本应用是否已获得 Shizuku 授权。 */
    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹出 Shizuku 授权对话框（需要在设备上点“允许”）。 */
    public static void request(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 以 shell 身份执行一条命令，返回 stdout（已 trim）。失败或未授权返回 null。
     */
    public static String sh(String cmd) {
        IRemoteProcess p = null;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) return null;
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            p = svc.newProcess(new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream());
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 一行结论，给界面直接显示。 */
    public static String statusLine() {
        if (!running()) return "未运行（需用 adb 启动服务）";
        StringBuilder sb = new StringBuilder("服务在线 v").append(version());
        sb.append(granted() ? "，已授权" : "，未授权");
        return sb.toString();
    }
}
