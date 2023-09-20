# AndroidBLESample

### BLE scanner

todo

---

### GATT

```
                     ┌ - - - - - - - -┐
                     ↓                |
disconnecting [ ]--→[ ] disconnected  |
               |     ├ - - - - - - - -┤
               |     ↓                |
               |    [ ] connecting    |
               |     ↓                |
               |---→[ ]← - - - - - - -* - - - - - - -┐
               |     |  search start  |              |
               |     ├ - - - - - - → [ ] search stop |
               |     |                ↑              |
               |     |                ├ - - - - - - -┘
               |     |                |
               |     ├ - - - - - - → [ ] search waiting
               |     ↓
               └ - -[ ] connected
```

---

- [x] Android `09` / api `28` / `P`
- [ ] Android `10` / api `29` / `Q`
- [ ] Android `11` / api `30` / `R`
- [ ] Android `12` / api `31` / `S`
