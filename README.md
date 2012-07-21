<!-- Uses markdown syntax for neat display at github -->

# RoverOpen
RoverOpen can be used to remotely control the AC13 Rover robot by the Brookstone® company. RoverOpen is not developed by Brookstone® but by a third-party developer. The current functionality:
* streaming from the camera (TCP/IP) on the robot towards your phone
* turn on/off the infrared light at the front
* move the robot by tilting your phone
* move the robot by using arrows

Make sure you can make an ad-hoc connection to the robot before you try this app! Phones that cannot connect to the robot (because they cannot connect to an ad-hoc network, as for example the HTC Wildfire) cannot use this app. You might need to root your phone (and e.g. use nice alternative firmware such as the [CyanogenMod](http://www.cyanogenmod.com/)).

## Is it good?
[RoverOpen](https://play.google.com/store/apps/details?id=org.almende.roveropen&hl=en) has been the first very Android app that can control the Brookstone® AC13 Rover robot. I created it during Christmas 2011. The code has been available from the beginning and has been used by others too (see for example [this Android app for tablets](https://play.google.com/store/apps/details?id=com.uceta.AC13Controller). I have been able to reverse engineer the video stream and most of the commands, but not to the audio stream yet. If you have time for that, feel free to contribute! The control might lag a little, which is also a point for improvement. It seems the robot can only be controlled by [bang-bang control](http://en.wikipedia.org/wiki/Bang-bang_control), but I might be mistaken.

## How to build?
This application makes it easy to use OpenCV together with your Android code for the Brookstone Rover. For that, run $NDK/ndkbuild in the project directory as explicated at http://www.stanford.edu/~zxwang/android_opencv.html.

## Screenshot
![RoverOpen Screenshot](https://lh6.ggpht.com/9YWHfrJJ5eRqRn5jtn0XPBCsMXM-JMDIs9RUMSrwE677L8tPnVuMcw2TmE4q325rlVI "RoverOpen Screenshot")

## Where can I read more?
* [Android Market (RoverOpen)](https://play.google.com/store/apps/details?id=org.almende.roveropen&hl=en)

## Copyrights
The copyrights (2012) belong to:

- Author: Anne van Rossum
- Date: 5 Jan. 2012
- License: LGPL v3
- Almende B.V., http://www.almende.com and DO bots B.V., http://www.dobots.nl
- Rotterdam, The Netherlands


