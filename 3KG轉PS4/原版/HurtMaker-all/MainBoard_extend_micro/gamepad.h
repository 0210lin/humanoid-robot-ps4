
/*
gamepad.h
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

#ifndef __GAMEPAD_H__
#define __GAMOPAD_H__

#include <Wire.h>

#define BTN_DIR_RIGHT     1
#define BTN_DIR_BACKWARD  2
#define BTN_DIR_LEFT      3
#define BTN_DIR_FORWARD   4

#define BTN_KEY_TRIANGLE  1
#define BTN_KEY_CIRCLES   2
#define BTN_KEY_CROSS     4
#define BTN_KEY_SQUARE    8
#define BTN_KEY_L1        16
#define BTN_KEY_R1        32
#define BTN_KEY_L2        64
#define BTN_KEY_R2        128
#define BTN_KEY_SELECT    256
#define BTN_KEY_START     512
#define BTN_KEY_L3        1024
#define BTN_KEY_R3        2048


void GP_init(void);
uint16_t GP_getkeyStatus(void);
uint16_t GP_getDir4Way(void);


#endif
