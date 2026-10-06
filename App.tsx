import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  AppState,
  Button,
  NativeModules,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

const {ProxyModule} = NativeModules;
const PORT = 1080;

type NetInfo = {ips: string[]; hotspotIp: string};

export default function App() {
  const [on, setOn] = useState(false);
  const [net, setNet] = useState<NetInfo>({ips: [], hotspotIp: ''});
  const [err, setErr] = useState<string | null>(null);
  const [testIp, setTestIp] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [testErr, setTestErr] = useState<string | null>(null);
  const onRef = useRef(on);
  onRef.current = on;

  const refresh = useCallback(async () => {
    if (!ProxyModule) {
      setErr('Native module nahi mila. Project dobara build karein.');
      return;
    }
    try {
      const [running, info] = await Promise.all([
        ProxyModule.isRunning(),
        ProxyModule.getNetworkInfo(),
      ]);
      setOn(running);
      setNet({ips: info.ips ?? [], hotspotIp: info.hotspotIp ?? ''});
      setErr(null);
    } catch (e) {
      setErr(String(e));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Hotspot on/off hote hi IP update ho jaye
  useEffect(() => {
    const id = setInterval(() => {
      if (onRef.current) {
        refresh();
      }
    }, 4000);
    return () => clearInterval(id);
  }, [refresh]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', state => {
      if (state === 'active') {
        refresh();
      }
    });
    return () => sub.remove();
  }, [refresh]);

  const toggle = () => {
    if (on) {
      ProxyModule.stopProxy();
      setOn(false);
    } else {
      ProxyModule.startProxy();
      setOn(true);
      refresh();
    }
  };

  const others = net.ips.filter(ip => ip !== net.hotspotIp);

  const testConnection = async () => {
    if (!ProxyModule?.getExitIp) {
      setTestErr('Is build mein yeh feature nahi hai — app dobara install karein.');
      return;
    }
    setTesting(true);
    setTestErr(null);
    setTestIp(null);
    try {
      setTestIp(await ProxyModule.getExitIp());
    } catch (e) {
      setTestErr(e instanceof Error ? e.message : String(e));
    } finally {
      setTesting(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.box}>
      <Text style={styles.title}>VPN Share</Text>

      <View style={[styles.badge, on ? styles.badgeOn : styles.badgeOff]}>
        <Text style={styles.badgeText}>{on ? 'Running' : 'Stopped'}</Text>
      </View>

      <View style={styles.btn}>
        <Button
          title={on ? 'Stop' : 'Start'}
          onPress={toggle}
          color={on ? '#c62828' : '#2e7d32'}
        />
      </View>

      {err != null && <Text style={styles.err}>{err}</Text>}

      {on && (
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Doosre device mein proxy set karein</Text>
          <Text style={styles.row}>Type: HTTP proxy + SOCKS5</Text>
          <Text style={styles.row}>Port: {PORT}</Text>

          {net.hotspotIp ? (
            <>
              <Text style={styles.row}>Host (hotspot IP):</Text>
              <Text style={styles.ip}>{net.hotspotIp}</Text>
            </>
          ) : (
            <Text style={styles.warn}>
              Hotspot IP nahi mila — phone ka Wi-Fi hotspot ON karein, phir yeh
              IP yahan dikhega.
            </Text>
          )}

          {others.length > 0 && (
            <Text style={styles.small}>
              Doosre IPs (aam tor par zaroorat nahi): {others.join(', ')}
            </Text>
          )}

          <Text style={styles.hint}>
            Doosre device ko phone ke hotspot se connect karein, phir us ke
            Wi-Fi settings mein: Proxy → Manual → Host aur Port dalein (HTTP
            proxy). Firefox mein SOCKS5 bhi chalega.
          </Text>
        </View>
      )}

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Connection test</Text>
        <Text style={styles.hint}>
          Phone ka exit IP nikaalta hai. Proxy ON ho to wahi IP doosre device ko
          milti chahiye. VPN band karke dobara test karein — IP zaroor badalni
          chahiye.
        </Text>
        <View style={styles.testBtn}>
          <Button
            title={testing ? 'Testing…' : 'Test connection'}
            onPress={testConnection}
            disabled={testing}
            color="#1565c0"
          />
        </View>
        {testIp != null && <Text style={styles.ip}>{testIp}</Text>}
        {testErr != null && <Text style={styles.err}>{testErr}</Text>}
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Zaroori baat</Text>
        <Text style={styles.hint}>
          1. Phone par pehle VPN app on karein, phir Start dabayein.{'\n'}
          2. VPN app ki allowed-apps list mein VPN Share shamil ho, warna proxy
          ka traffic VPN se nahi guzrega.{'\n'}
          3. Doosre device mein proxy sirf un apps ko milti hai jo system
          proxy (ya SOCKS5) support karte hain — browsers theek chalte hain,
          games/WhatsApp jaise apps nahi.{'\n'}
          4. PdaNet ya doosra tether app chalu ho to band kar dein, warna
          hotspot/VPN par conflict ho sakta hai.
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  box: {
    flexGrow: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    paddingTop: 60,
    backgroundColor: '#fafafa',
  },
  title: {fontSize: 28, fontWeight: '700', marginBottom: 12},
  badge: {
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 999,
    marginBottom: 16,
  },
  badgeOn: {backgroundColor: '#e8f5e9'},
  badgeOff: {backgroundColor: '#eeeeee'},
  badgeText: {fontWeight: '600'},
  btn: {minWidth: 160, marginBottom: 8},
  err: {color: '#c62828', marginTop: 12, textAlign: 'center'},
  warn: {color: '#e65100', marginTop: 8, lineHeight: 20},
  card: {
    width: '100%',
    maxWidth: 420,
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginTop: 20,
    borderWidth: 1,
    borderColor: '#e0e0e0',
  },
  cardTitle: {fontSize: 16, fontWeight: '700', marginBottom: 8},
  row: {fontSize: 15, marginTop: 2},
  ip: {
    fontSize: 20,
    fontWeight: '700',
    color: '#1565c0',
    marginTop: 4,
    fontFamily: 'monospace',
  },
  small: {fontSize: 12, color: '#777', marginTop: 8},
  testBtn: {marginTop: 10, minWidth: 180},
  hint: {fontSize: 13, color: '#555', marginTop: 12, lineHeight: 20},
});
