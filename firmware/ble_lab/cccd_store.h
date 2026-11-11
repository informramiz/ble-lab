// Subscription (CCCD) store sized for every bond the board keeps. NimBLE builds only.
//
// NimBLE persists the CCCD of every bonded peer, one record per subscribed characteristic. The
// Arduino-ESP32 3.3.x core ships a precompiled NimBLE with CONFIG_BT_NIMBLE_MAX_BONDS=3 but
// CONFIG_BT_NIMBLE_MAX_CCCDS=8, and this board has five CCCDs per peer (telemetry, motion, touch,
// OTA status, and the GATT Service Changed indication). A second phone's bond overflows the table:
// ble_store_config_write_cccd logs rc=27 (BLE_HS_ESTORE_CAP) and the host's overflow handler,
// ble_store_util_status_rr, deletes the oldest other bond to make room. Pairing an Android phone
// silently unpaired the iPhone, and the other way round.
//
// The sdkconfig value can't be changed without rebuilding the core, so this replaces only the CCCD
// part of the store: the host's store callbacks are pointed here, CCCD records go to a table with
// room for MAX_BONDS peers, persisted in NVS, and everything else (keys, IRKs, CSFC) still goes to
// NimBLE's own ble_store_config. Lookup rules match ble_store_config_find_cccd.
#pragma once

#if defined(CONFIG_NIMBLE_ENABLED)
#include <Arduino.h>
#include <string.h>
#include <nvs.h>
#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "store/config/ble_store_config.h"

namespace cccd_store {

static constexpr int PER_PEER = 8;  // 5 used today; headroom for new characteristics.
static constexpr int CAPACITY = MYNEWT_VAL(BLE_STORE_MAX_BONDS) * PER_PEER;
static constexpr const char *NVS_NAMESPACE = "blelab";
static constexpr const char *NVS_KEY = "cccd";

// BLE_ADDR_ANY is a C compound literal; C++ cannot take its address.
static const ble_addr_t ANY_PEER = {};

static ble_store_value_cccd records[CAPACITY];
static int count = 0;

static int find(const ble_store_key_cccd &key) {
  int skipped = 0;
  for (int i = 0; i < count; i++) {
    if (ble_addr_cmp(&key.peer_addr, &ANY_PEER) && ble_addr_cmp(&records[i].peer_addr, &key.peer_addr)) continue;
    if (key.chr_val_handle != 0 && records[i].chr_val_handle != key.chr_val_handle) continue;
    if (key.idx > skipped) {
      skipped++;
      continue;
    }
    return i;
  }
  return -1;
}

static int persist() {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) return BLE_HS_ESTORE_FAIL;
  esp_err_t err = count > 0 ? nvs_set_blob(h, NVS_KEY, records, count * sizeof(records[0])) : nvs_erase_key(h, NVS_KEY);
  if (err == ESP_ERR_NVS_NOT_FOUND) err = ESP_OK;  // Erasing a key that was never written.
  if (err == ESP_OK) err = nvs_commit(h);
  nvs_close(h);
  return err == ESP_OK ? 0 : BLE_HS_ESTORE_FAIL;
}

static bool isBonded(const ble_addr_t &peer) {
  ble_store_key_sec key = {};
  key.peer_addr = peer;
  ble_store_value_sec value;
  return ble_store_config_read(BLE_STORE_OBJ_TYPE_OUR_SEC, (const ble_store_key *)&key, (ble_store_value *)&value) == 0;
}

// Drops records whose peer has no bond left (written while bonding, then the bond failed).
static bool pruneOrphans() {
  int kept = 0;
  for (int i = 0; i < count; i++) {
    if (isBonded(records[i].peer_addr)) records[kept++] = records[i];
  }
  bool changed = kept != count;
  count = kept;
  return changed;
}

static int write(const ble_store_value_cccd &value) {
  ble_store_key_cccd key;
  ble_store_key_from_value_cccd(&key, &value);
  int i = find(key);
  if (i < 0) {
    if (count >= CAPACITY) pruneOrphans();
    // Still full: report it, and the host's overflow handler frees the oldest other bond.
    if (count >= CAPACITY) return BLE_HS_ESTORE_CAP;
    i = count++;
  }
  records[i] = value;
  return persist();
}

static int remove(const ble_store_key_cccd &key) {
  int i = find(key);
  if (i < 0) return BLE_HS_ENOENT;
  memmove(&records[i], &records[i + 1], (count - i - 1) * sizeof(records[0]));
  count--;
  return persist();
}

static int onRead(int type, const ble_store_key *key, ble_store_value *value) {
  if (type != BLE_STORE_OBJ_TYPE_CCCD) return ble_store_config_read(type, key, value);
  int i = find(key->cccd);
  if (i < 0) return BLE_HS_ENOENT;
  value->cccd = records[i];
  return 0;
}

static int onWrite(int type, const ble_store_value *value) {
  return type == BLE_STORE_OBJ_TYPE_CCCD ? write(value->cccd) : ble_store_config_write(type, value);
}

static int onDelete(int type, const ble_store_key *key) {
  return type == BLE_STORE_OBJ_TYPE_CCCD ? remove(key->cccd) : ble_store_config_delete(type, key);
}

// Call once after BLEDevice::init() and before advertising. Loads the table, moves any records
// NimBLE's own store still holds (firmware 1.3.8 and older kept them there) into it, then takes
// over the CCCD callbacks.
static void install() {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) == ESP_OK) {
    size_t size = sizeof(records);
    if (nvs_get_blob(h, NVS_KEY, records, &size) == ESP_OK) count = size / sizeof(records[0]);
    nvs_close(h);
  }
  ble_store_key_cccd any = {};
    ble_store_value_cccd old;
  bool moved = false;
  while (ble_store_config_read(BLE_STORE_OBJ_TYPE_CCCD, (const ble_store_key *)&any, (ble_store_value *)&old) == 0) {
    ble_store_key_cccd key;
    ble_store_key_from_value_cccd(&key, &old);
    if (find(key) < 0 && count < CAPACITY) records[count++] = old;
    moved = true;
    if (ble_store_config_delete(BLE_STORE_OBJ_TYPE_CCCD, (const ble_store_key *)&key) != 0) break;
  }
  if (pruneOrphans() || moved) persist();
  ble_hs_cfg.store_read_cb = onRead;
  ble_hs_cfg.store_write_cb = onWrite;
  ble_hs_cfg.store_delete_cb = onDelete;
}

// Serial console "bonds": bonded peers and stored subscriptions.
static void print() {
  ble_addr_t peers[MYNEWT_VAL(BLE_STORE_MAX_BONDS)];
  int n = 0;
  ble_store_util_bonded_peers(peers, &n, MYNEWT_VAL(BLE_STORE_MAX_BONDS));
  Serial.printf("bonds: %d of %d, subscriptions: %d of %d\n", n, MYNEWT_VAL(BLE_STORE_MAX_BONDS), count, CAPACITY);
  for (int p = 0; p < n; p++) {
    int subs = 0;
    for (int i = 0; i < count; i++) subs += ble_addr_cmp(&records[i].peer_addr, &peers[p]) == 0;
    const uint8_t *a = peers[p].val;
    Serial.printf("  peer %02x:%02x:%02x:%02x:%02x:%02x type=%u subscriptions=%d\n", a[5], a[4], a[3], a[2], a[1], a[0],
                  peers[p].type, subs);
  }
}

}  // namespace cccd_store
#endif
