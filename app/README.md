DMSARDemo - BETA
================

This is a sample app that provides an example integration of Digimarc Mobile SDK (DMSDK) with ARCore.

## Introduction

 DMSARDemo processes camera preview frames from the ARCore camera source, detecting Digimarc Barcode (Product Packaging, Thermal Label, Print Media, and Audio), the most common 1D barcodes found in retail, and QR codes. Detected codes, referred to as ```Payloads``` in Digimarc Mobile SDK (DMSDK), contain additional metadata (found in the ```DataDictionary``` object) that include its location within the input frame. DMSARDemo retrieves the location, converts it to view coordinates, and drops a node where the coordinates intersect with the scene's plane. Only one node will be rendered for each unique code, but will move when its position has changed by more than 5cm.

This sample app and support of Google's ARCore is BETA. We plan to continue to optimize our support as new capabilities and improvements are provided by Google ARCore and through feedback from our developer community. If you have feedback, please submit it here: https://www.digimarc.com/contact

 ## Prerequisites

You'll need a valid evaluation or commercial license key to use the core features of the app. Log in to your Digimarc Barcode Manager account to obtain your existing evaluation or commercial license key (https://portal.digimarc.net/). If your evaluation license is expired, please contact sales@digimarc.com to discuss obtaining a commercial license key.

 __Note:__ DMSARDemo requires a device capable of running ARCore. List of devices can be found here:  https://developers.google.com/ar/discover/supported-devices

 ## Screenshots

![Screenshot](dmsardemo.png)

## Getting started

1. Open the project with Android Studio
1. Replace the license placeholder in src/main/res/values/strings.xml file with your license key. 
1. Run the app on a connected device using Android Studio or, alternatively, invoke gradle directly using ```./gradlew installDebug```.