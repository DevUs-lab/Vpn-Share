import React, {useState} from 'react';
import {View, Text, Button, NativeModules, StyleSheet} from 'react-native';

const {ProxyModule} = NativeModules;

export default function App() {
  const [on, setOn] = useState(false);

  const toggle = () => {
    on ? ProxyModule.stopProxy() : ProxyModule.startProxy();
    setOn(!on);
  };

  return (
    <View style={styles.box}>
      <Text style={styles.title}>VPN Share</Text>
      <Text>Status: {on ? 'Running' : 'Stopped'}</Text>
      <Button title={on ? 'Stop' : 'Start'} onPress={toggle} />
      {on && <Text style={styles.note}>
        Doosre device mein SOCKS5 proxy set karein:{'\n'}
        Host: 192.168.43.1 (hotspot ka IP){'\n'}
        Port: 1080
      </Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  box: {flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20},
  title: {fontSize: 24, marginBottom: 12},
  note: {marginTop: 20, textAlign: 'center'},
});