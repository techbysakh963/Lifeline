#!/system/bin/sh
# Lifeline — READ-ONLY device capability probe for Android (POCO X7 Pro "rodin", KernelSU).
# Run as root:  su -c 'sh /sdcard/inspect_device.sh' > /sdcard/rodin_report.txt
# This script only reads files and runs getprop/ls/cat. It changes NOTHING.
sec() { printf '\n===== %s =====\n' "$1"; }
sec "IDENTITY"
for p in ro.product.model ro.product.device ro.product.name ro.product.manufacturer ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.mi.os.version.name ro.mi.os.version.code ro.build.fingerprint ro.board.platform ro.hardware ro.boot.hardware.platform; do printf '%s=%s\n' "$p" "$(getprop $p)"; done
sec "KERNEL"
uname -a
cat /proc/version
sec "ROOT / KERNELSU"
id
ls -ld /data/adb/ksu /data/adb/modules 2>&1
ls /data/adb/modules 2>&1
(ksud --version || /data/adb/ksud --version) 2>&1 | head -2
sec "USB STATE (properties)"
for p in sys.usb.config sys.usb.state persist.sys.usb.config sys.usb.controller sys.usb.configfs vendor.usb.config ro.boot.usbcontroller init.svc.adbd; do printf '%s=%s\n' "$p" "$(getprop $p)"; done
sec "UDC (USB device controller)"
ls -l /sys/class/udc 2>&1
for u in /sys/class/udc/*; do echo "-- $u"; cat "$u/state" "$u/current_speed" "$u/maximum_speed" 2>&1; done
sec "CONFIGFS GADGET"
mount | grep -E 'configfs' 2>&1
ls -l /config/usb_gadget 2>&1
for g in /config/usb_gadget/*; do
  [ -d "$g" ] || continue
  echo "-- gadget $g"; echo "UDC=$(cat $g/UDC 2>&1)"; echo "idVendor=$(cat $g/idVendor 2>&1) idProduct=$(cat $g/idProduct 2>&1)"
  echo "functions:"; ls -l "$g/functions" 2>&1
  echo "configs:"; ls -lR "$g/configs" 2>&1 | head -40
done
sec "FUNCTION DRIVERS AVAILABLE (a function dir can only be created if the driver is built in)"
ls /sys/module 2>/dev/null | grep -Ei 'usb_f_|u_ether|g_|configfs|libcomposite|usbnet|rndis|ncm|ecm|hid|acm|mass_storage|f_fs|ffs' 
sec "KALLSYMS hints for USB functions"
grep -Ei 'hidg|f_hid|f_acm|f_rndis|f_ncm|f_ecm|f_mass_storage|f_fs|f_mtp|f_ptp|usb_ep_|gserial' /proc/kallsyms 2>/dev/null | awk '{print $3}' | sed 's/\..*//' | sort -u | head -80
sec "ALLOCATED DEVICE NODES"
ls -l /dev/hidg* /dev/ttyGS* /dev/ttyACM* 2>&1
sec "/proc/config.gz (USB-related)"
if [ -r /proc/config.gz ]; then zcat /proc/config.gz | grep -E 'CONFIG_USB_(GADGET|CONFIGFS|F_|LIBCOMPOSITE|DWC3|MTU3|MUSB|ROLE_SWITCH|TYPEC)|CONFIG_TYPEC|CONFIG_USB_NET_RNDIS_HOST|CONFIG_USB_USBNET|CONFIG_HID|CONFIG_KSU|CONFIG_MODULES|CONFIG_MODVERSIONS'; else echo "/proc/config.gz not available (UNKNOWN: kernel config not exposed)"; fi
sec "USB ROLE / TYPE-C"
ls -R /sys/class/usb_role /sys/class/typec 2>&1 | head -40
for r in /sys/class/usb_role/*/role; do echo "$r: $(cat $r 2>&1)"; done
sec "NETWORK INTERFACES (USB tether shows as rndis0/ncm0/usb0)"
ip -br addr 2>&1
sec "SELINUX"
getenforce
sec "TETHER SERVICE"
dumpsys tethering 2>/dev/null | grep -Ei 'usb|rndis|ncm' | head -20
sec "END"
