# BLE connection management

## Topics to specify
- Scanning strategy and filters (names, service UUIDs)
- The connection limit across several batteries (Android's practical limit, about 7, varies by phone)
- Reconnect and backoff policy
- Clean disconnect guarantees (see the BW02 lockup in [ble-variants](../hardware/ble-variants.md))
- Handing the connection over to the official app or another phone
- Android permissions: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, notifications
