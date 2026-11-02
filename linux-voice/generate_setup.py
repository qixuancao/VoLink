#!/usr/bin/env python3
"""Write the private, fixed-port SonoBus voice preset (no credentials)."""
from pathlib import Path
import xml.etree.ElementTree as E

folder = Path.home() / '.config/android-linux-voice-bridge'
folder.mkdir(parents=True, exist_ok=True)
state = E.Element('SonoBusAoO')
parameters = {
    'dry': 0, 'wet': 1, 'defnetbuf': 80, 'defnetauto': 1,
    'defsendqual': 3, 'sendchannels': 1, 'mastsendmute': 0,
    'mastrecvmute': 0, 'mainreverbenabled': 0, 'metenabled': 0,
    'reconnectlast': 0,
}
for key, value in parameters.items():
    E.SubElement(state, 'PARAM', {'id': key, 'value': str(value)})
E.SubElement(state, 'ExtraState', {'UseUdpPort': '12001'})
properties = E.Element('PROPERTIES')
E.SubElement(properties, 'VALUE', {
    'name': 'filterStateXML', 'val': E.tostring(state, encoding='unicode'),
})
E.SubElement(E.SubElement(properties, 'VALUE', {'name': 'audioSetup'}), 'DEVICESETUP', {
    'deviceType': 'ALSA', 'audioOutputDeviceName': 'default',
    'audioInputDeviceName': 'default', 'audioDeviceRate': '48000',
    'audioDeviceBufferSize': '960', 'audioDeviceInChans': '1', 'audioDeviceOutChans': '11',
})
E.ElementTree(properties).write(folder / 'voice.sonobus', encoding='utf-8', xml_declaration=True)
(folder / 'alsa.conf').write_bytes(Path(__file__).with_name('alsa.conf').read_bytes())
