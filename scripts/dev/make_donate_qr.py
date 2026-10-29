"""DEV: render the donation QR (ink on white, Frost palette) for the app and the README.

Usage: python3 scripts/dev/make_donate_qr.py
Re-run only if the address changes, then update DONATE_ADDRESS in ui/Support.kt and README.md,
and check the output with `zbarimg -q --raw <png>`.
"""
import qrcode

ADDRESS = "0xfd64cbc4935dcce5b719e223469a84c83650b010"  # USDT, Ethereum (ERC-20)
INK, WHITE = (11, 13, 16), (255, 255, 255)

qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=16, border=2)
qr.add_data(ADDRESS)
qr.make(fit=True)
img = qr.make_image(fill_color=INK, back_color=WHITE).convert("RGB")
for out in ("app/src/main/res/drawable-nodpi/donate_usdt_qr.png", "docs/images/donate-usdt-erc20.png"):
    img.save(out, optimize=True)
    print(out, img.size)
