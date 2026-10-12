/*
gamepad_kondo.ino
Gatsby Jan
gatsby.jan@ppt.com.tw

Copyright (c) 2018 ~ 2019 CreatorArk technology

Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
and associated documentation files (the "Software"), to deal in the Software without restriction, 
including without limitation the rights to use, copy, modify, merge, publish, distribute, 
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is 
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or 
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING 
BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

#include "gamepad_kondo.h"

IcsSoftSerialClass krs(GAMEPAD_KONDO_RX_PIN, GAMEPAD_KONDO_TX_PIN, 
  GAMEPAD_KONDO_ENABLE_PIN, GAMEPAD_KONDO_BAUDRATE, GAMEPAD_KONDO_TIMEOUT); 

int16_t gpKondo_init(uint8_t id)
{
  if(id == PP_SENSOR_TYPE_GAMEPAD_KONDO){
   
    pp_sensor[id].isEnable = 1;
    krs.begin();
  }
  return 0;

}

int16_t gpKondo_enable(uint8_t id)
{
}

int16_t gpKondo_disable(uint8_t id)
{
}

uint32_t gccversion = (__GNUC__ * 10000L + __GNUC_MINOR__ * 100L + __GNUC_PATCHLEVEL__);
uint16_t gpKondo_getKey(uint8_t id)
{
  unsigned short keyTmp = 0;
  
  if(id != PP_SENSOR_TYPE_GAMEPAD_KONDO){
    return KRR_BUTTON_FALSE;
  }

  keyTmp = krs.getKrrButton();
  if(keyTmp != KONDO_BUTTON_FALSE){
    keyTmp &= 0xff7f;
  } 
  if((keyTmp != 65535) && (keyTmp != 0)){
    Serial.print("key=");
    Serial.println(keyTmp);
    //Serial.println(gccversion);
  }
  return keyTmp;
}

uint16_t btkey_sendData(uint8_t data)
{
  return 0;
}

