// JamGuard BLE bridge for classic ESP32 boards with BLE support.
// Install ESP32 board support in Arduino IDE. No extra BLE library needed.
// IMPORTANT: This is a transport integration sketch, not complete SX1278 detection firmware.
// Insert your EXISTING verified SX1278 RSSI and alarm logic in pollDetector().
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERVICE_UUID "c63c0001-92b0-4d7a-8ca8-13f57a6d0101"
#define DATA_UUID    "c63c0002-92b0-4d7a-8ca8-13f57a6d0101"
BLECharacteristic *dataChar = nullptr;
bool connected = false;
class ServerCallbacks: public BLEServerCallbacks {
 void onConnect(BLEServer*) override { connected=true; }
 void onDisconnect(BLEServer*) override { connected=false; BLEDevice::startAdvertising(); }
};
void sendLine(const String& line) {
 if (!connected || !dataChar) return;
 dataChar->setValue(line.c_str());
 dataChar->notify();
}
// Call from your existing detector code. Each ALARM is an alarm transition,
// not a repeated transmission on every sample.
void jamguardRssi(int dbm) { sendLine("RSSI," + String(dbm)); }
void jamguardAlarm(int dbm) { sendLine("ALARM," + String(dbm)); }
void jamguardClear(int dbm) { sendLine("CLEAR," + String(dbm)); }
void pollDetector() {
 // TODO: Integrate existing SX1278 radio driver and threshold/debounce logic here.
 // Do not invent RF readings or treat this template as a functioning jammer detector.
}
void setup() {
 Serial.begin(115200);
 BLEDevice::init("JamGuard-01");
 BLEServer *server=BLEDevice::createServer();
 server->setCallbacks(new ServerCallbacks());
 BLEService *service=server->createService(SERVICE_UUID);
 dataChar=service->createCharacteristic(DATA_UUID,BLECharacteristic::PROPERTY_READ|BLECharacteristic::PROPERTY_NOTIFY);
 dataChar->addDescriptor(new BLE2902());
 dataChar->setValue("RSSI,-127");
 service->start();
 BLEAdvertising *adv=BLEDevice::getAdvertising();
 adv->addServiceUUID(SERVICE_UUID);
 adv->start();
}
void loop() {
 pollDetector();
 delay(25);
}
