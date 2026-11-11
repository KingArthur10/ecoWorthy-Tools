# BLE connection management

## Rules learned from the spike (see docs/research/spike-bluez-jvm.md)
- Every failure path after `Connect()` must disconnect and wait for confirmation. BlueZ can finish a "failed" connect in the background.
- Before scanning, clear any connection left over from a previous run.
- Wait for `Notifying == true` before sending the first request.
- Run continuous passive scanning (which feeds signal strength), and pause it only while connecting.
- A failed poll is routine. Retry it next cycle and mark the data stale.

## Topics to specify
- Scanning strategy and filters (names, service UUIDs)
- The connection limit across several batteries (Android's practical limit, about 7, varies by phone)
- Reconnect and backoff policy
- Clean disconnect guarantees (see the BW02 lockup in [ble-variants](../hardware/ble-variants.md))
- Handing the connection over to the official app or another phone
- Android permissions: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, notifications
