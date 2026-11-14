# SANlight Bluetooth dimmer protocol

Observed on a SANlight EVO with Bluetooth dimmer `SLD V5`. *Unverified* marks
assumptions.

## Device

| | |
|---|---|
| Name | `SLD V5` |
| Composition data | CID `0x0A8B`, PID `0x0BD3`, VID `0x0005`, CRPL 40, relay + proxy |
| Elements | 1 |
| SIG models | `0x0000` Configuration Server, `0x0002` Health Server |
| Vendor model | `0x0A8B:0x0001`, AppKey index 0 |
| Default TTL | 4 |

No SIG lighting models (Generic OnOff, Generic Level, Light Lightness don't answer).

## Access

- Bluetooth Mesh via GATT proxy: service `0x1828`, Data In `0x2ADD` (write
  without response), Data Out `0x2ADE` (notify).
- Keys from the app's export (Settings > Mesh Maintenance > Export Mesh), Mesh
  Configuration Database 1.0.1: NetKey index 0, AppKey index 0, unicast
  addresses of the dimmers.
- Advertising: service data `0x1828`, type `0x00` + network ID. Manufacturer
  data `8b0a 01 d30b 0500 0200 <network id>`.
- One GATT connection at a time. While connected, the dimmer doesn't advertise.
- After connecting, the proxy sends a secure network beacon (IV index 0).
- The sender needs its own unicast address and a persisted, increasing sequence
  number (Mesh replay protection).

## Vendor messages

Opcode: `0xC0 | op`, `8B 0A` (3 bytes). Status opcode = request + 1. Values
little endian.

| Op | Request | Parameters | Status | Parameters |
|---|---|---|---|---|
| `0x01` | Set daylight cycle | daylight cycle | `0x02` | live status, `ff ff ff ff` if rejected |
| `0x03` | Get daylight cycle | – | `0x04` | daylight cycle |
| `0x06` | Set max brightness | u8 % | `0x07` | live status |
| `0x08` | Get max brightness | – | `0x09` | u8 % |
| `0x0A` | Set lamp clock | u32 ms | `0x0B` | live status |
| `0x0C` | Get live status | – | `0x0D` | live status |
| `0x0E` | Get status | – | `0x0F` | live status, u8 max brightness, daylight cycle |

Live status: u32 lamp clock (ms), u16 output (0.1 %).

Setters take missing parameters as 0: `0x06` without parameters turns the
light off, `0x0A` without parameters resets the lamp clock. Except `0x01`: it
answers `ff ff ff ff` and keeps the cycle.

Unknown, answered without parameters:

| Op | Status | Parameters |
|---|---|---|
| `0x11` | `0x12` | `00 00 00 00` |
| `0x13` | `0x14` | `00` |
| `0x15` | `0x16` | `05 00` |

No answer: `0x00`, `0x05`, `0x10`, `0x17`–`0x3F`, status opcodes (`0x07`
untested).

## Brightness

- Output = daylight cycle value at the lamp clock, capped by the max brightness.
- Linear between the cycle points: at 04:13:19 between 04:00 55 % and
  05:00 80 %, the output was 60.5 % (55 + 25 × 13.32 / 60 = 60.55).
- Max brightness: 0 (off) or 20–100 %. The lamp doesn't light below 20 %.
- The max brightness persists across connections.

## Lamp clock

- u32 ms, starts at 0 on power-on. Cycle time 00:00 = lamp clock 0.
- Lost on power loss by design: the lamp is meant to be switched by a timer at
  the socket.
- Can be set with `0x0A`.

## Daylight cycle

The dimmer keeps one cycle, the active one. `0x03` returns it, `0x01` replaces
it, both in this layout. The output follows a new cycle right away. The cycle
survives power loss; after power-on it runs from 00:00 again, with the lamp
clock.

| Bytes | Field |
|---|---|
| 4 | u32, *unverified:* ID |
| 1 | n, number of points, 2–16 |
| 3 × n | u16 minute of the day (0–1440), u8 % (0–100) |
| variable | name, UTF-8, NUL-terminated, at most 29 bytes |

```
"Default"     01000000 02 0000 64 a005 64 44656661756c7400
              00:00 100 %, 24:00 100 %

"Mid Flower"  2fbb843c 0b 0000 14 1e00 14 5a00 32 d200 4b f000 37 2c01 50
              5802 50 b202 14 d002 14 d102 00 a005 00 4d696420466c6f77657200
              00:00 20 %, 00:30 20 %, 01:30 50 %, 03:30 75 %, 04:00 55 %,
              05:00 80 %, 10:00 80 %, 11:30 20 %, 12:00 20 %, 12:01 0 %, 24:00 0 %
```

Writing:

- `0x01` with "Mid Flower" and 10:00 moved to 10:15 (5 segments): status
  `0x02` with the live status, `0x03` returns the cycle as sent.
- The dimmer keeps the ID as sent and takes IDs the app didn't make (tested
  with a new name and an ID of our own).
- The app shows no assigned cycle for one it didn't make. The dimmer runs it
  all the same.

What `0x01` does with invalid cycles (each followed by reading the cycle back;
the lamp clock kept running, no restart):

| Cycle | Answer | Cycle afterwards |
|---|---|---|
| 0, 1, 17–20, 24, 25, 30, 32, 33, 40, 48–50, 64, 65, 80, 95–97, 120, 123 points | `ff ff ff ff` | unchanged |
| a time of 24:01, 25:00 or minute 65535 | `ff ff ff ff` | unchanged |
| 101 % or 255 % | `ff ff ff ff` | unchanged |
| 2 or 16 points, also with a 29 byte name | live status | as sent |
| name of 30–64 bytes | live status | name cut after 29 bytes |
| empty name, UTF-8 name | live status | as sent |
| points not in order, same time twice | live status | as sent |
| without 00:00, without 24:00 | live status | as sent |

Every cycle the app made starts at 00:00, ends at 24:00, and its times
increase. sanvibe only writes such cycles, with a name of 1–29 bytes.

## Segmentation

- The dimmer sends at most 5 segments, then waits for a segment ack before it
  sends the rest. Partial messages must be acked once the burst pauses (`0x0F`
  with 11 cycle points has 6 segments).
- The dimmer acks segmented messages it receives.

## Open

- What the dimmer makes of a cycle with points not in order, or without 00:00
  or 24:00.
- Whether the app recognizes its cycles by the ID.
- Meaning of `0x11`, `0x13`, `0x15` (`05 00` = firmware version?).
- Meaning of the cycle's u32 (not CRC32, MD5, SHA-1 or Unix time of the content).
- VID: export says `0x0003`, device `0x0005` (firmware version?). Manufacturer
  data bytes `01` and `0200`.
- Lamp clock after 24 h.
- Max brightness after power loss.
- The max brightness once went from 30 % to 100 % without a write from us.
- Several dimmers in one mesh, group addresses.
