all:
	ant clean
	ant release
	jarsigner -verbose -keystore ~/.android/my-release-key.keystore bin/AccelService-unsigned.apk mhf
	zipalign -v 4 bin/AccelService-unsigned.apk bin/AccelService.apk 

clean: 
	ant clean


