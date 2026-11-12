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
 * Список сопряжённых Bluetooth-устройств с подключением/отключением по нажатию.
 * Управляется пультом (D-pad + OK).
 */
public class MainActivity extends Activity {

    private static final int HID_HOST = 4; // BluetoothProfile.HID_HOST (скрытая константа)
    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST};
    private static final String[] PROFILE_NAMES = {"A2DP", "HFP", "HID"};
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private final SparseArray<BluetoothProfile> proxies = new SparseArray<BluetoothProfile>();
    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bt;
    private TextView status;
    private DeviceAdapter listAdapter;
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

        ListView list = new ListView(this);
        listAdapter = new DeviceAdapter();
        list.setAdapter(listAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggle(devices.get(position));
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
        listAdapter.notifyDataSetChanged();
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

    // ---------- список ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class DeviceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public Object getItem(int position) {
            return devices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            BluetoothDevice d = devices.get(position);
            String profiles = connectedProfiles(d);
            boolean on = profiles.length() > 0 || isConnected(d);
            TextView t1 = (TextView) v.findViewById(android.R.id.text1);
            TextView t2 = (TextView) v.findViewById(android.R.id.text2);
            t1.setText((on ? "●  " : "○  ") + name(d));
            t1.setTextSize(20);
            t2.setText(kind(d) + "  ·  "
                    + (on ? "подключено" + (profiles.length() > 0 ? " (" + profiles + ")" : "")
                    : "не подключено")
                    + "  ·  " + d.getAddress());
            t2.setTextColor(on ? 0xFF66D17A : 0xFF9AA0A6);
            return v;
        }
    }
}
