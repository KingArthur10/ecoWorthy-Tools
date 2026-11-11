# Glossary

- **BMS:** battery management system, the board inside the battery that protects the cells
- **BLE:** Bluetooth Low Energy
- **SOC:** state of charge (%)
- **MOSFET (charge/discharge):** electronic switches in the BMS that enable or disable charging or discharging
- **Cell delta:** the difference between the highest and lowest cell voltage
- **BW02:** the ECO-WORTHY Bluetooth/WiFi module
- **JBD:** Jiabaida, a BMS maker (also sold as Xiaoxiang and Overkill Solar)
- **GATT:** the BLE protocol for services and characteristics
- **ADR:** architecture decision record
- **RSSI:** received signal strength, in dBm (closer to 0 is stronger)
- **Favorite:** a saved battery the app tracks, polls, and alarms on
- **Hub:** the Linux daemon that polls the batteries and serves the phone over the LAN
- **Hub mode / direct mode:** the phone reads through the hub, or straight from the batteries over Bluetooth
- **Profile:** a named set of charge limits (max, resume, reserve)
- **Hysteresis:** the gap between the cutoff and resume thresholds, which prevents rapid toggling
- **Bank:** a user-defined group of batteries wired in series and/or parallel
