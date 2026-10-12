// 印出 ESP32 的藍牙 MAC 位址(配對 PS4 手把時要用)
#include "esp_system.h"

void setup() {
  Serial.begin(115200);
  delay(1000);
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  Serial.printf("ESP32 Bluetooth MAC: %02x:%02x:%02x:%02x:%02x:%02x\n",
                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

void loop() {}
