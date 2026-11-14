# changes/

One fragment per ticket: `T<n>.md`, `A<n>.md` or `P<n>.md`. A fragment holds only `- ` bullets, each at most
two sentences, with no names and no history. The merger folds a fragment into `CHANGELOG.md`, bumps
`Config/Version.xcconfig` and deletes the fragment, so after a merge this folder holds only this file.
