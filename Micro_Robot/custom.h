#ifndef __CUSTOM_H__
#define __CUSTOM_H__

// custom.ino
void custom_setup();
void custom_loop();
int16_t custom_gamepadKeyFun_kondo();
void custom_stickFun();
void custom_stickUpdate();
void custom_imuFun();

// imu.ino
void imu_init();
void imu_update();
void imu_rearm();
uint8_t imu_present();
uint8_t imu_isEnabled();
uint8_t imu_cooling();
int16_t imu_getPitch10();
int16_t imu_getRoll10();
extern uint8_t IMU_FWD, IMU_BACK, IMU_LEFT, IMU_RIGHT;

// Micro_Robot.ino
void SetFrameRun(uint16_t frame, uint16_t delayms);
void SetFrameTilt(uint16_t standFrame, int16_t frameP, int16_t kP, int16_t frameR, int16_t kR, uint16_t timeMs);
uint16_t uart_disableMotor();
int16_t uart_isConnectToPC();
extern volatile uint8_t uart_isUartMode;

#endif
