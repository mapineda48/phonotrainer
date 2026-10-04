# Charis 7.000 (IPA font)

Unmodified copies of `web/Charis-Regular.woff2` and `web/Charis-Bold.woff2` from the
official Charis 7.000 release by SIL Global
(https://software.sil.org/charis/download/, https://github.com/silnrsi/font-charis/releases).

Licensed under the SIL Open Font License 1.1 (`OFL.txt` in this folder), with Reserved
Font Names "Charis" and "SIL". The files are shipped as-is — not subset or otherwise
modified — which is why they may keep the name "Charis" in `src/theme/fonts.css`.
If they are ever subset, the OFL requires renaming the modified font.

Why this font: it covers every symbol the analyzer emits, including the combining marks
U+031A (t̚), U+0303 (ɾ̃), U+0329/U+030D (n̩, ŋ̍), and ᵻ ‿ ˈ ː, and it positions the
diacritics correctly. The fontsource subsets of Charis, Gentium and Noto lack U+031A, ᵻ,
‿, ˈ and ː.
