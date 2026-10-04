/*
Micro_Robot.ino
Original: Gatsby Jan, CreatorArk technology (MIT license)

Arduino Micro 主程式:
  - 透過 Serial1 (500000) 控制伺服馬達
  - 透過 Serial (USB) 與電腦的 MotorAdj 工具溝通
  - 按鍵 -> 動作 的對照在 custom.ino,按鍵從 ESP32 來(pad.ino)
*/

#include <stdint.h>
#include <string.h>
#include "motor.h"
#include "pad.h"
#include "custom.h"

volatile uint16_t MotorPool[MOTOR_MOTOR_MAX][MOTOR_PARA_MAX] = {0x00};
volatile uint16_t motorPosMax = 2100, motorPosMin = 900;
volatile uint8_t Checksum_Calc;

#define UART_CMDBUF_MAX 128
uint8_t uart_CmdBuf[UART_CMDBUF_MAX];
volatile uint16_t uart_CmdBufIdx = 0;
volatile uint8_t uart_isUartMode = 0;
volatile uint8_t uart_cmdMotorId;

int16_t uart_getCmd();
int16_t uart_checkCmd();
uint16_t strToInt(uint8_t *str, uint8_t len);

void setup()
{
  Serial.begin(115200);
  delay(200);
  Serial1.begin(500000);
  while(!Serial1){;}

  custom_setup();
}

void loop()
{
  uint8_t motorChange = 0;

  custom_loop();

  while(1){
    if(uart_getCmd() == 0){
      if(uart_checkCmd() == 0){
        motorChange = 1;
      }
    }

    if(motorChange == 1){
      UART_Send_Frame(&MotorPool[0][0]);
    }
    motorChange = 0;
  }
}

// 播放 motor.h 裡的一個動作幀,並等待 delayms 毫秒
#define MOTOR_RESEND_TIME 20
void SetFrameRun(uint16_t frame, uint16_t delayms)
{
  if( frame >= MOTOR_FRAME_MAX ){
    return;
  }

  if(delayms <= MOTOR_RESEND_TIME){
    UART_Send_FrameByRom(&motor_para[frame][0][0]);
    delay(delayms);
  } else {
    do{
      UART_Send_FrameByRom(&motor_para[frame][0][0]);
      delay(MOTOR_RESEND_TIME);
      delayms -= MOTOR_RESEND_TIME;
    } while(delayms >= MOTOR_RESEND_TIME);
    delay(delayms);
  }
}

// 依傾斜量混合兩個「浮誇姿勢」,只動它們有勾選(啟用)的馬達:
//   位置 = 站姿 + (前後傾姿勢 - 站姿) x kP / 1000 + (左右傾姿勢 - 站姿) x kR / 1000
// kP、kR:-1000 ~ 1000(負的 = 反方向,等於把姿勢左右 / 前後反過來),frame 給 -1 代表不用那一組。
// timeMs:馬達走到位置要花多久(毫秒),愈小動得愈快。
void SetFrameTilt(uint16_t standFrame, int16_t frameP, int16_t kP, int16_t frameR, int16_t kR, uint16_t timeMs)
{
  if(standFrame >= MOTOR_FRAME_MAX){
    return;
  }
  Checksum_Calc = 0;
  UART_Send_MotionHeader();
  for(uint8_t ii = 0; ii < 32; ii++){
    uint16_t pos = 0;
    uint16_t tm = 0;
    if(ii < MOTOR_MOTOR_MAX){
      int32_t stand = pgm_read_word_near(&motor_para[standFrame][ii][MOTOR_POS]);
      int32_t p = stand;
      uint8_t used = 0;
      if(frameP >= 0 && frameP < MOTOR_FRAME_MAX && pgm_read_word_near(&motor_para[frameP][ii][MOTOR_EN]) != 0){
        p += ((int32_t)pgm_read_word_near(&motor_para[frameP][ii][MOTOR_POS]) - stand) * kP / 1000;
        used = 1;
      }
      if(frameR >= 0 && frameR < MOTOR_FRAME_MAX && pgm_read_word_near(&motor_para[frameR][ii][MOTOR_EN]) != 0){
        p += ((int32_t)pgm_read_word_near(&motor_para[frameR][ii][MOTOR_POS]) - stand) * kR / 1000;
        used = 1;
      }
      if(used){
        if(p < 500){ p = 500; }
        if(p > 2500){ p = 2500; }
        pos = (uint16_t)p;
        tm = timeMs;
      }
    }
    UART_Send_PosAndTime(ii + 1, pos, tm);
  }
  UART_Send_Checksum();
  Serial1.flush();
}

/* ===== 與電腦 MotorAdj 的通訊協定(格式固定,不要改) ===== */

#define UART_CMD_ACK  "MCMA"
#define UART_CMD_ACK_SET_END      (strlen(UART_CMD_ACK))  // 4 byte

#define UART_CMD_DUMMY_POWER  "MDYP"
#define UART_CMD_DUMMY_POWER_IDX        (strlen(UART_CMD_DUMMY_POWER))  // 4 byte
#define UART_CMD_DUMMY_POWER_IDX_CNT    (1)
#define UART_CMD_DUMMY_POWER_ID         (UART_CMD_DUMMY_POWER_IDX + UART_CMD_DUMMY_POWER_IDX_CNT)  // 2 byte
#define UART_CMD_DUMMY_POWER_ID_CNT     (2)
#define UART_CMD_DUMMY_POWER_ENABLE       (UART_CMD_DUMMY_POWER_ID + UART_CMD_DUMMY_POWER_ID_CNT)  // 1 byte
#define UART_CMD_DUMMY_POWER_ENABLE_CNT   (1)
#define UART_CMD_DUMMY_POWER_POSITION     (UART_CMD_DUMMY_POWER_ENABLE + UART_CMD_DUMMY_POWER_ENABLE_CNT)  // 4 byte
#define UART_CMD_DUMMY_POWER_POSITION_CNT (4)
#define UART_CMD_DUMMY_POWER_SPEED        (UART_CMD_DUMMY_POWER_POSITION + UART_CMD_DUMMY_POWER_POSITION_CNT)  // 4 byte
#define UART_CMD_DUMMY_POWER_SPEED_CNT    (4)
#define UART_CMD_DUMMY_POWER_END          (UART_CMD_DUMMY_POWER_SPEED + UART_CMD_DUMMY_POWER_SPEED_CNT)

#define UART_CMD_DUMMY_DATA  "MDYD"
#define UART_CMD_DUMMY_DATA_IDX      (strlen(UART_CMD_DUMMY_DATA))  // 4 byte
#define UART_CMD_DUMMY_DATA_IDX_CNT  (1)
#define UART_CMD_DUMMY_DATA_ID       (UART_CMD_DUMMY_DATA_IDX + UART_CMD_DUMMY_DATA_IDX_CNT)  // 2 byte
#define UART_CMD_DUMMY_DATA_ID_CNT   (2)
#define UART_CMD_DUMMY_DATA_END      (UART_CMD_DUMMY_DATA_ID + UART_CMD_DUMMY_DATA_ID_CNT)

#define UART_CMD_SENSOR  "MCMS"
#define UART_CMD_SENSOR_IDX         (strlen(UART_CMD_SENSOR))  // 4 byte
#define UART_CMD_SENSOR_IDX_CNT     (1)
#define UART_CMD_SENSOR_ID          (UART_CMD_SENSOR_IDX + UART_CMD_SENSOR_IDX_CNT)  // 2 byte
#define UART_CMD_SENSOR_ID_CNT      (2)
#define UART_CMD_SENSOR_ENABLE      (UART_CMD_SENSOR_ID + UART_CMD_SENSOR_ID_CNT)  // 1 byte
#define UART_CMD_SENSOR_ENABLE_CNT  (1)
#define UART_CMD_SENSOR_SET_END     (UART_CMD_SENSOR_ENABLE + UART_CMD_SENSOR_ENABLE_CNT)
#define UART_SENSOR_ID_MAX          (11)

#define UART_CMD_BEGIN  "MCMB"
#define UART_CMD_BEGIN_SET_MAX      (strlen(UART_CMD_BEGIN))  // 4 byte
#define UART_CMD_BEGIN_SET_MAX_CNT  (4)
#define UART_CMD_BEGIN_SET_MIN      (UART_CMD_BEGIN_SET_MAX + UART_CMD_BEGIN_SET_MAX_CNT)  // 4 byte
#define UART_CMD_BEGIN_SET_MIN_CNT  (4)
#define UART_CMD_BEGIN_SET_END      (UART_CMD_BEGIN_SET_MIN + UART_CMD_BEGIN_SET_MIN_CNT)

#define UART_CMD_MOTORSET "MS"
#define UART_CMD_SET_CMDIDX_IDX     (strlen(UART_CMD_MOTORSET))  // 1 byte
#define UART_CMD_SET_CMDIDX_IDX_CNT (1)
#define UART_CMD_SET_CHA_IDX        (UART_CMD_SET_CMDIDX_IDX + UART_CMD_SET_CMDIDX_IDX_CNT)  // 2 byte
#define UART_CMD_SET_CHA_IDX_CNT    (2)
#define UART_CMD_SET_EN_IDX         (UART_CMD_SET_CHA_IDX + UART_CMD_SET_CHA_IDX_CNT)    // 1 byte
#define UART_CMD_SET_EN_IDX_CNT     (1)
#define UART_CMD_SET_POS_IDX        (UART_CMD_SET_EN_IDX + UART_CMD_SET_EN_IDX_CNT)     // 4 bytes
#define UART_CMD_SET_POS_IDX_CNT    (4)
#define UART_CMD_SET_TIME_IDX       (UART_CMD_SET_POS_IDX + UART_CMD_SET_POS_IDX_CNT)    // 5 bytes
#define UART_CMD_SET_TIME_IDX_CNT   (5)
#define UART_CMD_SET_END_IDX        (UART_CMD_SET_TIME_IDX + UART_CMD_SET_TIME_IDX_CNT)    // 1 bytes
#define UART_CMD_SET_END_IDX_CNT    (1)
#define UART_CMD_SET_END           (UART_CMD_SET_END_IDX + UART_CMD_SET_END_IDX_CNT)

// 回傳 "標頭 + 4 位數字"
static void uart_replyNum(const char *tag, uint16_t v)
{
  char b[8];
  memcpy(b, tag, 4);
  b[4] = '0' + (v / 1000) % 10;
  b[5] = '0' + (v / 100) % 10;
  b[6] = '0' + (v / 10) % 10;
  b[7] = '0' + v % 10;
  Serial.write(b, 8);
  Serial.flush();
}

int16_t uart_isConnectToPC()
{
  if(uart_getCmd() == 0){
    uart_checkCmd();

    return uart_isUartMode;
  }
  return 0;
}

void uart_clearCmdBuf()
{
  memset(uart_CmdBuf, 0x00, UART_CMDBUF_MAX);
  uart_CmdBufIdx = 0;
}

int16_t uart_getCmd()
{
  while(Serial.available()){
    uart_CmdBuf[uart_CmdBufIdx] = Serial.read();
    if(++uart_CmdBufIdx >= UART_CMDBUF_MAX){
      uart_clearCmdBuf();
    }
    return 0;
  }

  return -1;
}

int16_t uart_cmdBegin()
{
  uint8_t * targetStr;
  if((targetStr = (uint8_t*)strstr((char*)uart_CmdBuf, UART_CMD_BEGIN)) != 0){

    uint16_t tmpMax, tmpMin;
    tmpMax = strToInt(targetStr+UART_CMD_BEGIN_SET_MAX, UART_CMD_BEGIN_SET_MAX_CNT);
    tmpMin = strToInt(targetStr+UART_CMD_BEGIN_SET_MIN, UART_CMD_BEGIN_SET_MIN_CNT);
    if(tmpMax <= tmpMin || tmpMax > 3000 || tmpMin > 3000 ||
      tmpMax == 0 || tmpMin == 0){
      return -3;
    }
    motorPosMax = tmpMax;
    motorPosMin = tmpMin;

    uart_clearCmdBuf();
    uart_isUartMode = 1;

    Serial.write(UART_CMD_BEGIN, strlen(UART_CMD_BEGIN));
    Serial.flush();
    return 0;
  } else {
    return -2;
  }
}

int16_t uart_cmdAck()
{
  if(strstr((char*)uart_CmdBuf, UART_CMD_ACK) != 0){
    uart_clearCmdBuf();

    Serial.write(UART_CMD_ACK, strlen(UART_CMD_ACK));
    Serial.flush();
    return 0;
  } else {
    return -2;
  }
}

int16_t uart_cmdMotor()
{
  uint8_t * targetStr;
  if((targetStr = (uint8_t*)strstr((char*)uart_CmdBuf, UART_CMD_MOTORSET)) != 0){
    uint16_t channel = strToInt(targetStr+UART_CMD_SET_CHA_IDX, UART_CMD_SET_CHA_IDX_CNT);
    uint16_t en      = strToInt(targetStr+UART_CMD_SET_EN_IDX, UART_CMD_SET_EN_IDX_CNT);
    uint16_t pos     = strToInt(targetStr+UART_CMD_SET_POS_IDX, UART_CMD_SET_POS_IDX_CNT);
    uint16_t time    = strToInt(targetStr+UART_CMD_SET_TIME_IDX, UART_CMD_SET_TIME_IDX_CNT);

    uart_clearCmdBuf();

    if(channel >= MOTOR_MOTOR_MAX){
      return -4;
    }
    if(en != 0 && en != 1){
      return -5;
    }
    if(pos > motorPosMax || pos < motorPosMin){
      return -6;
    }

    MotorPool[channel][MOTOR_EN] = en;
    MotorPool[channel][MOTOR_POS] = pos;
    MotorPool[channel][MOTOR_SPEED] = time;

    return 0;
  } else {
    return -7;
  }
}

// 感測器查詢:本機器人沒有接感測器,固定回覆 0(保留是為了相容 MotorAdj)
int16_t uart_cmdSensor()
{
  uint8_t * targetStr;
  if((targetStr = (uint8_t*)strstr((char*)uart_CmdBuf, UART_CMD_SENSOR)) != 0){
    uint8_t sensorId = strToInt(targetStr+UART_CMD_SENSOR_ID, UART_CMD_SENSOR_ID_CNT);
    uint16_t en = strToInt(targetStr+UART_CMD_SENSOR_ENABLE, UART_CMD_SENSOR_ENABLE_CNT);

    uart_clearCmdBuf();

    if(sensorId >= UART_SENSOR_ID_MAX){
      return -4;
    }
    if(en != 0 && en != 1){
      return -5;
    }

    uart_replyNum(UART_CMD_SENSOR, 0);
    return 0;
  } else {
    return -7;
  }
}

int16_t uart_cmdDummyMotorPower()
{
  uint8_t * targetStr;
  uint16_t uart_cmdPos;
  uint16_t uart_cmdSpeed;
  uint16_t uart_cmdEn;

  if((targetStr = (uint8_t*)strstr((char*)uart_CmdBuf, UART_CMD_DUMMY_POWER)) != 0){
    uart_cmdMotorId = strToInt(targetStr + UART_CMD_DUMMY_POWER_ID, UART_CMD_DUMMY_POWER_ID_CNT);
    uart_cmdEn = strToInt(targetStr + UART_CMD_DUMMY_POWER_ENABLE, UART_CMD_DUMMY_POWER_ENABLE_CNT);
    uart_cmdPos = strToInt(targetStr + UART_CMD_DUMMY_POWER_POSITION, UART_CMD_DUMMY_POWER_POSITION_CNT);
    uart_cmdSpeed = strToInt(targetStr + UART_CMD_DUMMY_POWER_SPEED, UART_CMD_DUMMY_POWER_SPEED_CNT);

    uart_clearCmdBuf();

    if(uart_cmdMotorId > MOTOR_MOTOR_MAX){
      return -4;
    }
    if(uart_cmdEn != 0 && uart_cmdEn != 1){
      return -5;
    }

    while(Serial1.available()){
        Serial1.read();
    }

    if(uart_cmdEn == 1){
      UART_Send_SetMotorPosition(uart_cmdMotorId, uart_cmdPos, uart_cmdSpeed);
    } else {
      UART_Send_SetMotorPosition(uart_cmdMotorId, 0, 0);
    }

    Serial.write(UART_CMD_DUMMY_POWER, strlen(UART_CMD_DUMMY_POWER));
    Serial.flush();

    return 0;
  } else {
    return -7;
  }
}

int16_t uart_cmdDummyMotorPosition()
{
  uint8_t * targetStr;
  uint8_t tmp_motorId;

  if((targetStr = (uint8_t*)strstr((char*)uart_CmdBuf, UART_CMD_DUMMY_DATA)) != 0){
    tmp_motorId = strToInt(targetStr + UART_CMD_DUMMY_DATA_ID, UART_CMD_DUMMY_DATA_ID_CNT);

    uart_clearCmdBuf();

    if(tmp_motorId > MOTOR_MOTOR_MAX){
      return -4;
    }

    if(tmp_motorId != uart_cmdMotorId){
      return -5;
    }

    UART_Send_SetMotorPosition(tmp_motorId, 0, 0);

    delay(5);

    while(Serial1.available()){
        Serial1.read();
    }

    UART_Send_GetMotorPosiiton(uart_cmdMotorId);

#define UART_MOTORBUG_MAX 32
#define UART_MOTOR_POS_RET_LEN  11

    uint8_t tmp_GetUartBuf[UART_MOTORBUG_MAX] = {0};
    uint16_t tmp_GetUartBufIdx = 0;

    for(uint8_t ii = 0; ii < 5; ii ++){
      while(Serial1.available()){
        tmp_GetUartBuf[tmp_GetUartBufIdx++] = Serial1.read();
      }
      if(tmp_GetUartBufIdx >= UART_MOTORBUG_MAX){
        break;
      }
      delay(1);
    }

    if( tmp_GetUartBufIdx < UART_MOTOR_POS_RET_LEN){
      return -8;
    }

    for(uint8_t ii = 0; ii < tmp_GetUartBufIdx; ii++){
      if(tmp_GetUartBuf[ii] == (0x80 + uart_cmdMotorId) &&
        tmp_GetUartBuf[ii + 1] == 0x91 &&
        tmp_GetUartBuf[ii + 2] == 0x03){
        uint16_t tmp_pos = (uint16_t)tmp_GetUartBuf[ii + 3] * 256 + (uint16_t)tmp_GetUartBuf[ii + 4];

        uart_replyNum(UART_CMD_DUMMY_DATA, tmp_pos);

        break;
      }
    }

    return 0;
  } else {
    return -7;
  }
}

int16_t uart_checkCmd()
{
  if( uart_isUartMode == 0 ){
    if(strlen((char*)uart_CmdBuf) < UART_CMD_BEGIN_SET_END){
      return -1;
    }
    return uart_cmdBegin();

  } else {
    int retValue = 0;

    if(strlen((char*)uart_CmdBuf) >= UART_CMD_ACK_SET_END){
      retValue = uart_cmdAck();
      if(retValue == 0) {
        return retValue;
      }
    }
    if(strlen((char*)uart_CmdBuf) >= UART_CMD_BEGIN_SET_END){
      retValue = uart_cmdBegin();
      if(retValue == 0) {
        return retValue;
      }
    }
    if(strlen((char*)uart_CmdBuf) >= UART_CMD_SENSOR_SET_END){
      retValue = uart_cmdSensor();
      if(retValue == 0) {
        return retValue;
      }
    }
    if(strlen((const char*)uart_CmdBuf) >= UART_CMD_SET_END){
      retValue = uart_cmdMotor();
      if(retValue == 0) {
        return retValue;
      }
    }

    if(strlen((const char*)uart_CmdBuf) >= UART_CMD_DUMMY_POWER_END){
      retValue = uart_cmdDummyMotorPower();
      if(retValue == 0) {
        return 1;
      }
    }

    if(strlen((const char*)uart_CmdBuf) >= UART_CMD_DUMMY_DATA_END){
      retValue = uart_cmdDummyMotorPosition();
      if(retValue == 0) {
        return 1;
      }
    }

    return -3;
  }
  return -1;
}

uint16_t strToInt(uint8_t *str, uint8_t len)
{
  uint16_t tmp = 0;

  for(uint8_t ii = 0; ii < len; ii++){
    tmp *= 10;
    tmp += (*(str + ii) - '0');
  }

  return tmp;
}

/* ===== 伺服馬達匯流排 (Serial1) ===== */

uint16_t uart_disableMotor()
{
  memset( &MotorPool[0][0], 0x00, sizeof(MotorPool) );
  UART_Send_Frame( &MotorPool[0][0] );
  Serial1.flush();
  delay(500);
  return 0;
}

void UART_Send_Frame( uint16_t * FrameData )
{
  uint16_t pos = 0;
  uint16_t time = 0;

  Checksum_Calc = 0;
  UART_Send_MotionHeader();
  uint8_t ii;
  for(ii = 0 ; ii < 32; ii ++){
    if(ii >= MOTOR_MOTOR_MAX){
      pos = 0;
      time = 0;
    } else {
      if(*(FrameData + ii * MOTOR_PARA_MAX + MOTOR_EN) == 0){
        pos = 0;
        time = 0;
      } else {
        pos = *(FrameData + ii * MOTOR_PARA_MAX + MOTOR_POS);
        time = *(FrameData + ii * MOTOR_PARA_MAX + MOTOR_SPEED);
      }
    }

    UART_Send_PosAndTime( ii + 1, pos, time );
  }

  UART_Send_Checksum();
  Serial1.flush();
}

void UART_Send_FrameByRom( uint16_t * FrameData )
{
  uint16_t pos = 0;
  uint16_t time = 0;

  Checksum_Calc = 0;
  UART_Send_MotionHeader();
  uint8_t ii;
  for(ii = 0 ; ii < 32; ii ++){
    if(ii >= MOTOR_MOTOR_MAX){
      pos = 0;
      time = 0;
    } else {
      if(pgm_read_word_near(FrameData + ii * MOTOR_PARA_MAX + MOTOR_EN) == 0){
        pos = 0;
        time = 0;
      } else {
        pos = pgm_read_word_near(FrameData + ii * MOTOR_PARA_MAX + MOTOR_POS);
        time = pgm_read_word_near(FrameData + ii * MOTOR_PARA_MAX + MOTOR_SPEED);
      }
    }
    UART_Send_PosAndTime( ii + 1, pos, time );
  }
  UART_Send_Checksum();
  Serial1.flush();
}

void UART_Send_MotionHeader(void)
{
  UART_Send(0x80 | 0x00);//header mark & broadcast ID
  UART_Send(0x80 | 0x82);//header mark & commaand code
  UART_Send(0xA2);//total data length
  UART_Send(0x05);//data length (one servo with time and speed)
}

void UART_Send_PosAndTime(uint8_t ID, uint16_t Position, uint16_t Time)
{
  UART_Send(ID); //ServoID
  UART_Send((Position / 256) & 0x7F); //Servo Pos_H
  UART_Send(Position % 256);  //Servo Pos_L
  UART_Send((Time / 256) & 0x7F); //Servo Time_H
  UART_Send(Time % 256);  //Servo Time_L
}

void UART_Send_Checksum()
{
  Serial1.write(Checksum_Calc);
  Serial1.write(0xff);

  Checksum_Calc = 0;
}

void UART_Send(uint8_t u8_byte_data)
{
  Serial1.write(u8_byte_data);
  Checksum_Calc += u8_byte_data;
}

void UART_Send_SetMotorPosition(uint16_t motorId, uint16_t Postion, uint16_t Time)
{
  Checksum_Calc = 0;
  UART_Send(0x80 + motorId);    //header mark & broadcast ID
  UART_Send(0x83);              //header mark & commaand code
  UART_Send(0x05);              //total data length
  UART_Send((Postion / 256) & 0x7F);  //Servo Pos_H
  UART_Send(Postion % 256);           //Servo Pos_L
  UART_Send((Time / 256) & 0x7F); //Servo Time_H
  UART_Send(Time % 256);          //Servo Time_L
  UART_Send(Checksum_Calc);       //data length (one servo with time and speed)
}

void UART_Send_GetMotorPosiiton(uint16_t motorId)
{
  Checksum_Calc = 0;
  UART_Send(0x80 + motorId);    //header mark & broadcast ID
  UART_Send(0x91);              //header mark & commaand code
  UART_Send(0x01);              //return data length
  UART_Send(0x05);              //start address
  UART_Send(Checksum_Calc);     //data length (one servo with time and speed)
}
