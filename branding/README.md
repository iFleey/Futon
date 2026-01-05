Futon Branding Assets
==================================

RESTRICTED ASSETS
-----------------

The images in this directory are the official branding for Futon.

Copyright:    (C) 2025 Fleey & Caniv
License:      Non-commercial, referential use only.
Commercial use requires written permission.
Restriction:  You may NOT bundle these assets in ANY modified version (forks),
regardless of commercial or non-commercial intent.

See the root COPYRIGHT file for the full policy.


ASSET INVENTORY
---------------

Official Assets (branding/official/):

branding/official/logo.svg
Description: Vector logo (source file)
SHA-256:     30d554edfdc7eb72e60639dc083bbe258ee923147155a7e59aa54347135f9836

Community Assets (branding/community/):

This directory is reserved for community-contributed branding that forks
may use freely. Currently empty. Contributions welcome.


FOR FORK MAINTAINERS
--------------------

If you distribute a modified version of this software, you MUST:

1. REMOVE all official branding:

- DELETE the directory "branding/official/"
- Use assets from "branding/community/" or create your own

2. REPLACE application identity:

- Change the application name (do not use "Futon" or similar)
- Update APP_NAME and SERVICE_NAME in daemon/core/branding.h
- Change the Android package name in app/build.gradle.kts

3. UPDATE app resources:

- app/src/main/res/mipmap-*/ic_launcher*.webp
- app/src/main/res/drawable/logo*.xml

4. CLEARLY identify your fork:

- Do not claim your fork is "Futon" or "Official Futon"
- Clearly indicate that your version is a derivative work
- Preserve original copyright notices in source files

RATIONALE
---------

This physical separation exists to:

- Prevent accidental misuse: Fork maintainers can simply delete
  "branding/official/"

- Remove plausible deniability: No one can claim they "didn't know
  which files to remove"

- Protect users: Prevents malicious forks from impersonating the
  official release

- Enable community: The "community/" folder provides a clear path
  for alternative branding

This policy follows the model established by Mozilla Firefox
(which Debian renamed to IceWeasel) and Chromium/Chrome.


CONTACT
-------

For trademark licensing inquiries or permission to use official Futon branding,
contact the original author through the project repository.

Repository: https://github.com/iFleey/Futon
