/*
  custom.ino

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
#include "motor.h"

#include "custom.h"
#include "gamepad_kondo.h"

volatile uint8_t btn_mode = BTN_MODE_NONE;

uint8_t auto_edge_cnt = 0;
uint8_t auto_turn_cnt = 0;
uint8_t auto_fight_cnt = 0;

int16_t auto_time_cnt = 0;
uint32_t auto_millis_cnt = 0;


int mode = 1;
int timer = 0;

void custom_setup()
{
  //krs.begin();
  gpKondo_init(PP_SENSOR_TYPE_GAMEPAD_KONDO);

}

void custom_loop()
{

  uint16_t btn_key = 0;

  uart_disableMotor();

  // wait the key to start machine
  while ((btn_key = gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO)) != KONDO_BUTTON_START) {
    //Serial.println(btn_key);
    if (uart_isConnectToPC() != 0) {
      return;
    }
  }

  while (uart_isUartMode == 0) {
    custom_gamepadKeyFun_kondo();

    if (uart_isConnectToPC() != 0) {
      return;
    }
  }

}

int16_t custom_gamepadKeyFun_kondo()
{
  unsigned short buttonData = KONDO_BUTTON_FALSE;

  buttonData = gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO);
  if (buttonData != KONDO_BUTTON_FALSE)
  {
    switch (buttonData)
    {

      case KONDO_BUTTON_RIGHT :
        {
          if (mode == 1) {
            do {
              SetFrameRun(14, 60);
              SetFrameRun(12, 80);


            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(12, 50);
            SetFrameRun(59, 100);
          } else if (mode == 2) {
            do {
              SetFrameRun(64, 60);
              SetFrameRun(62, 80);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 50);
            SetFrameRun(61, 100);
          }
          break;
        }

      case KONDO_BUTTON_DOWN :
        {
          if (mode == 1) {
            uint8_t jumpFlag = 0;

            SetFrameRun(2, 100);
            SetFrameRun(3, 80);
            do {
              SetFrameRun(4, 75);
              SetFrameRun(5, 105);

              if (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) != KONDO_BUTTON_DOWN) {
                jumpFlag = 1;
                break;
              }
              SetFrameRun(6, 75);
              SetFrameRun(7, 105);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) == KONDO_BUTTON_DOWN);

            if (jumpFlag == 1) {
              SetFrameRun(5, 50);
              SetFrameRun(6, 65);
            } else {
              SetFrameRun(7, 50);
              SetFrameRun(4, 65);
            }

            SetFrameRun(59, 150);
          } else if (mode == 2) {

          }
          break;
        }

      case KONDO_BUTTON_LEFT :
        {
          if (mode == 1) {
            do {
              SetFrameRun(13, 60);
              SetFrameRun(12, 80);


            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(12, 50);
            SetFrameRun(59, 100);
          } else if (mode == 2) {
            do {
              SetFrameRun(63, 60);
              SetFrameRun(62, 80);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 50);
            SetFrameRun(61, 100);
          }
          break;
        }

      case KONDO_BUTTON_UP:
        {
          if (mode == 1) {
            uint8_t jumpFlag = 0;

            SetFrameRun(2, 100);
            SetFrameRun(3, 80);
            do {
              SetFrameRun(8, 65);

              if (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) == (KONDO_BUTTON_L1 | KONDO_BUTTON_UP)) {
                SetFrameRun(48, 90);
              } else {
                SetFrameRun(9, 95);
              }

              buttonData = gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO);
              if ((buttonData != KONDO_BUTTON_UP) &&
                  (buttonData != (KONDO_BUTTON_R1 | KONDO_BUTTON_UP)) &&
                  (buttonData != (KONDO_BUTTON_L1 | KONDO_BUTTON_UP))
                 ) {
                jumpFlag = 1;
                break;
              }
              SetFrameRun(10, 65);

              if (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) == (KONDO_BUTTON_R1 | KONDO_BUTTON_UP)) {
                SetFrameRun(49, 90);
              } else {
                SetFrameRun(11, 95);
              }

              buttonData = gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO);
            } while ((buttonData == KONDO_BUTTON_UP) ||
                     (buttonData == (KONDO_BUTTON_R1 | KONDO_BUTTON_UP)) ||
                     (buttonData == (KONDO_BUTTON_L1 | KONDO_BUTTON_UP))
                    );

            if (jumpFlag == 1) {

              SetFrameRun(10, 65);
            } else {

              SetFrameRun(8, 65);
            }
            SetFrameRun(59, 150);

          } else if (mode == 2) {

          }

          break;
        }
      case KONDO_BUTTON_TRIANGLE:
        {
          if (mode == 1 ) {
            SetFrameRun(20, 800);
            SetFrameRun(21, 50);
          } else if ( mode == 2) {

          }
          break;
        }
      case KONDO_BUTTON_CROSS:
        {
          if (mode == 1) {
            SetFrameRun(18, 100);
            do {
              SetFrameRun(19, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(1, 150);
          } else if (mode == 2) {

          }
          break;
        }

      case KONDO_BUTTON_CIRCLE:
        {
          if (mode == 1) {
            SetFrameRun(31, 50);
            do {
              SetFrameRun(32, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(59, 150);
          } else if (mode == 2) {
            SetFrameRun(68, 50);
            do {
              SetFrameRun(69, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 150);
            SetFrameRun(61, 150);
          }
          break;
        }

      case KONDO_BUTTON_SQUARE:
        {
          if (mode == 1) {
            SetFrameRun(33, 50);
            do {
              SetFrameRun(34, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(59, 150);
          } else if (mode == 2) {
            SetFrameRun(70, 50);
            do {
              SetFrameRun(71, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 150);
            SetFrameRun(61, 150);
          }
          break;
        }

      case KONDO_BUTTON_R1:
        {
          if (mode == 1) {
            SetFrameRun(35, 100);
            do {
              SetFrameRun(36, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(59, 150);
          } else if (mode == 2) {

          }
          break;

        }
      case KONDO_BUTTON_L1:
        {
          if (mode == 1) {
            SetFrameRun(37, 100);
            do {
              SetFrameRun(38, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(59, 150);
          } else if (mode == 2) {

          }
          break;
        }


      case KONDO_BUTTON_L2:
        {
          if (mode == 1) {
            do {
              SetFrameRun(16, 110);
              SetFrameRun(15, 70);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(1, 150);
          } else if (mode == 2) {
            do {
              SetFrameRun(67, 110);
              SetFrameRun(65, 70);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 150);
            SetFrameRun(61, 150);
          }
          break;
        }

      case KONDO_BUTTON_R2:
        {
          if (mode == 1) {
            do {
              SetFrameRun(17, 110);
              SetFrameRun(15, 70);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(1, 150);
          } else if (mode == 2) {
            do {
              SetFrameRun(66, 110);
              SetFrameRun(65, 70);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 150);
            SetFrameRun(61, 150);
          }
          break;
        }

      case KONDO_BUTTON_STOP :
        {
          uart_disableMotor();
          break;
        }

      case KONDO_BUTTON_START :
        {
          if (mode == 1) {
            SetFrameRun(1, 500);
          } else if (mode == 2) {
            SetFrameRun(60, 250);
            SetFrameRun(61, 100);
          }

          break;
        }



      case KONDO_BUTTON_LEFT | KONDO_BUTTON_DOWN:
        {
          if (mode == 1) {
            SetFrameRun(30, 150 );
            SetFrameRun(22, 200 );
            SetFrameRun(23, 200);
            SetFrameRun(24, 200);
            do {
              SetFrameRun(53, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(25, 150);
            SetFrameRun(1, 150);
          } else if (mode == 2) {
            SetFrameRun(52, 300 );
            SetFrameRun(55, 300);
            SetFrameRun(56, 200);
            do {
              SetFrameRun(57, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 150);
            SetFrameRun(61, 150);
          }
          break;
        }

      case KONDO_BUTTON_CIRCLE | KONDO_BUTTON_CROSS:
        {
          if (mode == 1) {
            SetFrameRun(30, 150 );
            SetFrameRun(26, 200);
            SetFrameRun(27, 200);
            SetFrameRun(28, 200);
            do {
              SetFrameRun(54, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(29, 150);
            SetFrameRun(1, 150);
          } else if (mode == 2) {
            SetFrameRun(52, 300 );
            SetFrameRun(58, 300);
            SetFrameRun(72, 200);
            do {
              SetFrameRun(73, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(60, 150);
            SetFrameRun(61, 150);
          }
          break;
        }

      case KONDO_BUTTON_CIRCLE | KONDO_BUTTON_TRIANGLE:
        {
          if (mode == 1) {
            SetFrameRun(39, 50);
            SetFrameRun(40, 100);

            do {
              SetFrameRun(41, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(39, 200);
            SetFrameRun(1, 150);
          } else if (mode == 2) {

          }
          break;

        }

      case KONDO_BUTTON_UP | KONDO_BUTTON_LEFT:
        {
          if (mode == 1) {
            SetFrameRun(42, 50);
            SetFrameRun(43, 100);
            do {
              SetFrameRun(44, 20);
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(42, 200);
            SetFrameRun(1, 150);
          } else if (mode == 2) {

          }
          break;
        }

      case KONDO_BUTTON_RIGHT | KONDO_BUTTON_DOWN:
        {

          if (mode == 1) {
            do {
              delay(10);
              timer = timer + 1;
              if (timer >= 100) {
                mode = 2;
                SetFrameRun(60, 250);
                SetFrameRun(61, 100);
              }
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            timer = 0;
          } else {
            break;
          }

          break;

        }

      case KONDO_BUTTON_UP | KONDO_BUTTON_RIGHT:

        {
          if (mode == 2) {
            do {
              delay(10);
              timer = timer + 1;
              if (timer >= 100) {
                mode = 1;
                SetFrameRun(1, 250);
              }
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            timer = 0;
          } else {

          }
          break;

        }
      case KONDO_BUTTON_SQUARE | KONDO_BUTTON_CROSS:
        {
          if (mode == 1) {
            SetFrameRun(47, 500);
            SetFrameRun(48, 200);
            do {
              SetFrameRun(49, 20 );
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(50, 100);
            SetFrameRun(51, 200);
          } else if (mode == 2) {

          }
          break;
        }
      case KONDO_BUTTON_TRIANGLE | KONDO_BUTTON_SQUARE:

        {
          if (mode == 1) {
            do {
              SetFrameRun(45, 20);
              if (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) == (KONDO_BUTTON_R1 | KONDO_BUTTON_TRIANGLE | KONDO_BUTTON_SQUARE)) {
                SetFrameRun(46, 500);
              }
              else {
                SetFrameRun(45, 20);
              }
            } while (gpKondo_getKey(PP_SENSOR_TYPE_GAMEPAD_KONDO) > 0);
            SetFrameRun(1, 150);

          } else if (mode == 2) {

          }
          break;
        }


      default:
        {
          Serial.println(" buttonData");
          break;
        }
    }
  }
  else
  {

  }

  delay(10); //KRR5 10ms

  return 0;
}
