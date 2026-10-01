#ifndef __CUSTOM_H__
#define __CUSTOM_H__

// custom.ino
void custom_setup();
void custom_loop();
int16_t custom_gamepadKeyFun_kondo();
void custom_stickFun();
void custom_stickUpdate();

// Micro_Robot.ino
void SetFrameRun(uint16_t frame, uint16_t delayms);
uint16_t uart_disableMotor();
int16_t uart_isConnectToPC();
extern volatile uint8_t uart_isUartMode;

#endif
