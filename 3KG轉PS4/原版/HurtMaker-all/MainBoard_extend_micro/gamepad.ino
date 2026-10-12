/*
gamepad.ino
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


#include "gamepad.h"

#define GP_CMD_LONG_PRESS      0x29
#define GP_CMD_DIR_LONG_PRESS  0x2a

#define GP_SLAVE_ADDRESS  0x29

void GP_init()
{
  Wire.begin();    
}

//GP_CMD_LONG_PRESS
uint16_t GP_getkeyStatus()
{
  Wire.beginTransmission(GP_SLAVE_ADDRESS);
  Wire.write(GP_CMD_LONG_PRESS);
  Wire.endTransmission();

  Wire.requestFrom(GP_SLAVE_ADDRESS, 2);
  while (2 >= Wire.available()) {
    uint16_t reading = 0;
    
    reading = Wire.read();
    reading |= (Wire.read() << 8);
    
    if(reading == 0xff || reading == 0xffff || reading == 0xff00){
      return 0;
    } else {
      return reading;
    }
  }
  
  return 0;
}

//GP_CMD_DIR_LONG_PRESS
uint16_t GP_getDir4Way()
{ 
  Wire.beginTransmission(GP_SLAVE_ADDRESS);
  Wire.write(GP_CMD_DIR_LONG_PRESS);
  Wire.endTransmission();
  
  Wire.requestFrom(GP_SLAVE_ADDRESS, 1);
  while (Wire.available()) {
    uint16_t reading = 0;
    
    reading = Wire.read();
    if(reading == 0xff || reading == 0xffff){
      return 0;
    } else {
      return reading;
    }
  }
  return 0;
}


