package com.btreconnect;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Список сопряжённых Bluetooth-устройств с подключением/отключением по нажатию
 * и выбором вывода звука, когда подключено аудиоустройство.
 * Управляется пультом (D-pad + OK).
 */
public class MainActivity extends Activity {

    private static final int HID_HOST = 4; // BluetoothProfile.HID_HOST (скрытая константа)
    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST};
    private static final String[] PROFILE_NAMES = {"A2DP", "HFP", "HID"};
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    // Скрытые константы android.media.AudioSystem
    private static final int FOR_MEDIA = 1;
    private static final int FORCE_NONE = 0;
    private static final int FORCE_NO_BT_A2DP = 10;

    private static final int ROW_HEADER = 0;
    private static final int ROW_OUTPUT = 1; // device == null — встроенный вывод
    private static final int ROW_DEVICE = 2;

    private static final class Row {
        final int type;
        final BluetoothDevice device;
        final String title;

        Row(int type, BluetoothDevice device, String title) {
            this.type = type;
            this.device = device;
            this.title = title;
        }

        String key() {
            return type + ":" + (device != null ? device.getAddress() : title);
        }
    }

    private final SparseArray<BluetoothProfile> proxies = new SparseArray<BluetoothProfile>();
    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private final List<Row> rows = new ArrayList<Row>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bt;
    private TextView status;
    private ListView list;
    private DeviceAdapter listAdapter;
    /** Куда сейчас идёт звук: адрес устройства, "" — встроенный вывод, null — неизвестно. */
    private String currentOut;
    private boolean receiverRegistered;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scheduleRefresh(300);
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            proxies.put(profile, proxy);
            scheduleRefresh(0);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            proxies.remove(profile);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(32);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Сопряжённые Bluetooth-устройства");
        title.setTextSize(26);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(12));
        status.setText("OK — подключить / отключить");
        root.addView(status);

        list = new ListView(this);
        listAdapter = new DeviceAdapter();
        list.setAdapter(listAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Row row = rows.get(position);
                if (row.type == ROW_OUTPUT) selectOutput(row.device);
                else if (row.type == ROW_DEVICE) toggle(row.device);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        list.requestFocus();

        bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) {
            status.setText("На этом устройстве нет Bluetooth");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(PERM_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{PERM_CONNECT}, 1);
        } else {
            openProxies();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            openProxies();
            refresh();
        } else {
            status.setText("Нет разрешения «Устройства поблизости» — выдайте его в настройках приложения");
        }
    }

    private void openProxies() {
        for (int profile : PROFILES) {
            try {
                bt.getProfileProxy(this, profileListener, profile);
            } catch (Throwable ignored) {
                // профиль не поддерживается прошивкой
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED");
        f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED");
        registerReceiver(receiver, f);
        receiverRegistered = true;
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        ui.removeCallbacks(refreshTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bt != null) {
            for (int i = 0; i < proxies.size(); i++) {
                try {
                    bt.closeProfileProxy(proxies.keyAt(i), proxies.valueAt(i));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void scheduleRefresh(long delayMs) {
        ui.removeCallbacks(refreshTask);
        ui.postDelayed(refreshTask, delayMs);
    }

    private void refresh() {
        if (bt == null) return;
        int pos = list.getSelectedItemPosition();
        String selected = pos >= 0 && pos < rows.size() ? rows.get(pos).key() : null;

        devices.clear();
        try {
            if (!bt.isEnabled()) {
                status.setText("Bluetooth выключен");
            } else {
                Set<BluetoothDevice> bonded = bt.getBondedDevices();
                if (bonded != null) devices.addAll(bonded);
                if (devices.isEmpty()) status.setText("Нет сопряжённых устройств");
            }
        } catch (SecurityException e) {
            status.setText("Нет разрешения на Bluetooth");
        }
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                return name(a).compareToIgnoreCase(name(b));
            }
        });

        rows.clear();
        List<BluetoothDevice> audio = audioOutputs();
        currentOut = currentOutput(audio);
        if (!audio.isEmpty()) { // выбирать есть из чего, только когда подключено аудиоустройство
            rows.add(new Row(ROW_HEADER, null, "Вывод звука"));
            rows.add(new Row(ROW_OUTPUT, null, "Встроенный вывод (динамики / HDMI)"));
            for (BluetoothDevice d : audio) rows.add(new Row(ROW_OUTPUT, d, null));
            rows.add(new Row(ROW_HEADER, null, "Устройства"));
        }
        for (BluetoothDevice d : devices) rows.add(new Row(ROW_DEVICE, d, null));
        listAdapter.notifyDataSetChanged();

        // секция вывода появляется и исчезает — держим выделение на той же строке
        if (selected != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (i != pos && rows.get(i).key().equals(selected)) {
                    list.setSelection(i);
                    break;
                }
            }
        }
    }

    // ---------- состояние ----------

    private String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && n.length() > 0) return n;
        } catch (Throwable ignored) {
        }
        return d.getAddress();
    }

    private String kind(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c != null) {
                int major = c.getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return "Аудио";
                if (major == BluetoothClass.Device.Major.PERIPHERAL) return "Геймпад / клавиатура / пульт";
                if (major == BluetoothClass.Device.Major.PHONE) return "Телефон";
                if (major == BluetoothClass.Device.Major.COMPUTER) return "Компьютер";
            }
        } catch (Throwable ignored) {
        }
        return "Устройство";
    }

    /** Список подключённых профилей, например "A2DP, HID"; пустая строка — не подключено. */
    private String connectedProfiles(BluetoothDevice d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROFILES.length; i++) {
            BluetoothProfile p = proxies.get(PROFILES[i]);
            if (p == null) continue;
            try {
                if (p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(PROFILE_NAMES[i]);
                }
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private boolean isConnected(BluetoothDevice d) {
        if (connectedProfiles(d).length() > 0) return true;
        try { // скрытый BluetoothDevice.isConnected() — ловит BLE-пульты и т.п.
            Method m = BluetoothDevice.class.getMethod("isConnected");
            return Boolean.TRUE.equals(m.invoke(d));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------- подключение ----------

    private void toggle(BluetoothDevice d) {
        if (isConnected(d)) {
            int n = callOnAll("disconnect", d);
            status.setText(n > 0 ? "Отключаю: " + name(d)
                    : "Не удалось отключить " + name(d) + " (прошивка не разрешает)");
        } else {
            status.setText("Подключаю: " + name(d) + "…");
            callOnAll("setPriority", d); // на старых Android профиль мог быть выключен
            int n = callOnAll("connect", d);
            if (n == 0) {
                status.setText("Прямое подключение запрещено прошивкой, пробую разбудить: " + name(d) + "…");
                nudge(d);
            }
        }
        scheduleRefresh(1500);
        ui.postDelayed(refreshTask, 5000);
    }

    /** Вызывает скрытый метод профиля через рефлексию. Возвращает число профилей, ответивших true. */
    private int callOnAll(String method, BluetoothDevice d) {
        int ok = 0;
        for (int profile : PROFILES) {
            BluetoothProfile p = proxies.get(profile);
            if (p == null) continue;
            try {
                Object r;
                if ("setPriority".equals(method)) {
                    Method m = p.getClass().getMethod(method, BluetoothDevice.class, int.class);
                    r = m.invoke(p, d, 100); // PRIORITY_ON
                } else {
                    Method m = p.getClass().getMethod(method, BluetoothDevice.class);
                    r = m.invoke(p, d);
                }
                if (Boolean.TRUE.equals(r)) ok++;
            } catch (Throwable ignored) {
                // SecurityException / NoSuchMethodException — зависит от версии Android
            }
        }
        return ok;
    }

    /**
     * Запасной путь для Android 11+, где connect() требует системного разрешения:
     * поднимаем радиоканал до устройства публичными API. Многие колонки и наушники,
     * увидев знакомый хост, сами поднимают A2DP.
     */
    private void nudge(final BluetoothDevice d) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    d.fetchUuidsWithSdp();
                } catch (Throwable ignored) {
                }
                BluetoothSocket s = null;
                try {
                    bt.cancelDiscovery();
                    s = d.createInsecureRfcommSocketToServiceRecord(SPP);
                    s.connect();
                    Thread.sleep(2000);
                } catch (Throwable ignored) {
                    // у колонки обычно нет SPP — это нормально, канал уже был поднят
                } finally {
                    try {
                        if (s != null) s.close();
                    } catch (Throwable ignored) {
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                        status.setText(isConnected(d) ? "Подключено: " + name(d)
                                : "Не подключилось: " + name(d) + ". Проверьте, что устройство включено");
                    }
                });
            }
        }).start();
    }

    // ---------- вывод звука ----------

    /** Сопряжённые устройства с подключённым A2DP — на них можно направить звук. */
    private List<BluetoothDevice> audioOutputs() {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        BluetoothProfile a2dp = proxies.get(BluetoothProfile.A2DP);
        if (a2dp == null) return out;
        for (BluetoothDevice d : devices) {
            try {
                if (a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) out.add(d);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Активное A2DP-устройство (скрытый метод, Android 9+). Исключение — узнать не удалось. */
    private BluetoothDevice activeA2dp() throws Exception {
        BluetoothProfile a2dp = proxies.get(BluetoothProfile.A2DP);
        return (BluetoothDevice) a2dp.getClass().getMethod("getActiveDevice").invoke(a2dp);
    }

    private String currentOutput(List<BluetoothDevice> audio) {
        if (audio.isEmpty() || mediaForceUse() == FORCE_NO_BT_A2DP) return "";
        try {
            BluetoothDevice active = activeA2dp();
            return active == null ? "" : active.getAddress();
        } catch (Throwable ignored) {
            // до Android 9 метода нет, но и A2DP-устройство там может быть только одно
            return audio.size() == 1 ? audio.get(0).getAddress() : null;
        }
    }

    /** Текущая принудительная маршрутизация медиазвука; -1 — узнать не удалось. */
    private static int mediaForceUse() {
        try {
            Class<?> c = Class.forName("android.media.AudioSystem");
            return (Integer) c.getMethod("getForceUse", int.class).invoke(null, FOR_MEDIA);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Скрытый AudioSystem.setForceUse; true — настройка действительно применилась. */
    private static boolean setMediaForceUse(int config) {
        try {
            Class<?> c = Class.forName("android.media.AudioSystem");
            c.getMethod("setForceUse", int.class, int.class).invoke(null, FOR_MEDIA, config);
        } catch (Throwable ignored) {
            return false;
        }
        return mediaForceUse() == config;
    }

    /** Делает устройство активным для звука; true — запрос принят или оно уже активно. */
    private boolean makeActive(BluetoothDevice d) {
        try {
            if (d.equals(activeA2dp())) return true;
        } catch (Throwable ignored) {
            if (audioOutputs().size() == 1) return true; // единственное — оно и играет
        }
        try { // Android 9–12
            BluetoothProfile a2dp = proxies.get(BluetoothProfile.A2DP);
            Method m = a2dp.getClass().getMethod("setActiveDevice", BluetoothDevice.class);
            if (Boolean.TRUE.equals(m.invoke(a2dp, d))) return true;
        } catch (Throwable ignored) {
        }
        try { // Android 13+; 0 = ACTIVE_DEVICE_AUDIO
            Method m = BluetoothAdapter.class.getMethod("setActiveDevice", BluetoothDevice.class, int.class);
            if (Boolean.TRUE.equals(m.invoke(bt, d, 0))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Направляет звук на устройство; null — на встроенный вывод, не разрывая Bluetooth. */
    private void selectOutput(BluetoothDevice target) {
        if (target == null) {
            status.setText(setMediaForceUse(FORCE_NO_BT_A2DP)
                    ? "Звук: встроенный вывод (динамики / HDMI)"
                    : "Прошивка не даёт переключить вывод. Чтобы вернуть звук на динамики / HDMI, "
                    + "отключите аудиоустройство в списке ниже");
        } else {
            boolean routed = mediaForceUse() != FORCE_NO_BT_A2DP || setMediaForceUse(FORCE_NONE);
            status.setText(routed && makeActive(target)
                    ? "Звук: " + name(target)
                    : "Прошивка не даёт переключить звук на " + name(target)
                    + ". Отключите остальные аудиоустройства — звук перейдёт на оставшееся");
        }
        scheduleRefresh(700);
        ui.postDelayed(refreshTask, 3000);
    }

    // ---------- список ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class DeviceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type == ROW_HEADER ? 0 : 1;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) { // заголовки пульт перешагивает
            return rows.get(position).type != ROW_HEADER;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.type == ROW_HEADER) {
                TextView h = (TextView) convertView;
                if (h == null) {
                    h = new TextView(MainActivity.this);
                    h.setTextSize(14);
                    h.setTextColor(0xFF9AA0A6);
                    h.setPadding(dp(16), dp(14), dp(16), dp(4));
                }
                h.setText(row.title.toUpperCase());
                return h;
            }

            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            TextView t1 = (TextView) v.findViewById(android.R.id.text1);
            TextView t2 = (TextView) v.findViewById(android.R.id.text2);
            t1.setTextSize(20);
            BluetoothDevice d = row.device;

            if (row.type == ROW_OUTPUT) {
                boolean on = (d == null ? "" : d.getAddress()).equals(currentOut);
                t1.setText((on ? "◉  " : "◯  ") + (d == null ? row.title : name(d)));
                t2.setText(on ? "звук идёт сюда" : "OK — переключить звук сюда");
                t2.setTextColor(on ? 0xFF66D17A : 0xFF9AA0A6);
                return v;
            }

            String profiles = connectedProfiles(d);
            boolean on = profiles.length() > 0 || isConnected(d);
            t1.setText((on ? "●  " : "○  ") + name(d));
            t2.setText(kind(d) + "  ·  "
                    + (on ? "подключено" + (profiles.length() > 0 ? " (" + profiles + ")" : "")
                    : "не подключено")
                    + "  ·  " + d.getAddress());
            t2.setTextColor(on ? 0xFF66D17A : 0xFF9AA0A6);
            return v;
        }
    }
}
