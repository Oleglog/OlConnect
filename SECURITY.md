# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem. OlConnect ships a
VPN client, and a public report exposes every user of the current release.

Use the private channel: this repository's **Security** tab →
**Report a vulnerability**. The report is visible only to the maintainers.

If the button is missing, the repository owner has not enabled private
reporting yet — in that case open a public issue that says only "security
report, need a private channel", with no technical detail, and the maintainer
will open one.

## What is in scope

- leaks of the exit node address or traffic through the VPN boundary:
  split tunnelling bypasses, `SO_BINDTODEVICE` bindings, DNS and ICMP escapes
  (see issue #5 — treated as real, not as a theoretical Android quirk);
- anything that lets an app reach the tunnel without being in the per-app
  policy;
- key handling: room keys, profile encryption, keystore use, diagnostics
  exports that carry secrets;
- update and subscription paths that would let a third party substitute a
  profile or a binary.

Out of scope: carriers blocking the tunnel (that is a censorship problem, not
a vulnerability), and the upstream Android rule that any app can bind to a
VPN interface unless the client filters it — the client filters, so a report
that shows the filter failing is in scope.

## What to expect

- acknowledgement within 72 hours;
- a fix or a mitigation plan before any public write-up;
- credit in the release notes if you want it.
