/* 2.4: NAS (24.501 5GS / 24.301 EPS) renderer for the signaling page.
   Header, security wrapper, message name and IE list; a few IEs are interpreted (causes, DNN/APN, S-NSSAI,
   PDU session id, registration/update types, timers, identity TYPE). Identities, keys/nonces, addresses,
   PCO, TFT/QoS rules (contain IP filters), SMS/LPP containers and unknown IEs are masked by default. */
struct nas_ie { uint8_t iei; uint8_t fmt; uint8_t len; uint8_t flags; const char *name; };
/* fmt: 1 TV1(half octet IEI) 2 T(type 2) 3 TV(len incl. IEI) 4 TLV 5 TLV-E
         mandatory: 10 V(len) 11 LV 12 LV-E 13 V half octets (one octet carrying 2 values) */
enum { NF_MASK = 1, NF_DNN = 2, NF_CAUSE5MM = 3, NF_CAUSE5SM = 4, NF_CAUSEEMM = 5, NF_CAUSEESM = 6, NF_SNSSAI = 7, NF_PSI = 8,
       NF_ID5G = 9, NF_IDEPS = 10, NF_PAYLOAD = 11, NF_NASC5G = 12, NF_NASCEPS = 13, NF_ESMC = 14, NF_TIMER2 = 15, NF_TIMER3 = 16,
       NF_NSSAI = 17, NF_REGTYPE = 18, NF_QCI = 19, NF_PCT = 20, NF_HEXLEN = 21 };
struct nas_msg { uint8_t pd; uint8_t type; uint8_t dir; const char *name; const struct nas_ie *ie; };  /* dir 0 any 1 UL 2 DL */

#define IE(i, f, l, fl, n) { i, f, l, fl, n }
#define END { 0, 0, 0, 0, NULL }
/* ---- 5GMM ---- */
static const struct nas_ie k5_regreq[] = { IE(0, 13, 1, 0, "ngKSI / 5GS registration type"), IE(0, 12, 0, NF_ID5G, "5GS mobile identity"),
    IE(0xC0, 1, 1, 0, "Non-current native NAS key set identifier"), IE(0x10, 4, 0, 0, "5GMM capability"), IE(0x2E, 4, 0, 0, "UE security capability"),
    IE(0x2F, 4, 0, NF_NSSAI, "Requested NSSAI"), IE(0x52, 3, 7, 0, "Last visited registered TAI"), IE(0x17, 4, 0, 0, "S1 UE network capability"),
    IE(0x40, 4, 0, 0, "Uplink data status"), IE(0x50, 4, 0, 0, "PDU session status"), IE(0xB0, 1, 1, 0, "MICO indication"),
    IE(0x2B, 4, 0, 0, "UE status"), IE(0x77, 5, 0, NF_ID5G, "Additional GUTI"), IE(0x25, 4, 0, 0, "Allowed PDU session status"),
    IE(0x18, 4, 0, 0, "UE's usage setting"), IE(0x51, 4, 0, 0, "Requested DRX parameters"), IE(0x70, 5, 0, NF_NASCEPS, "EPS NAS message container"),
    IE(0x74, 5, 0, 0, "LADN indication"), IE(0x80, 1, 1, 0, "Payload container type"), IE(0x7B, 5, 0, NF_MASK, "Payload container"),
    IE(0x90, 1, 1, 0, "Network slicing indication"), IE(0x53, 4, 0, 0, "5GS update type"), IE(0x41, 4, 0, 0, "Mobile station classmark 2"),
    IE(0x42, 4, 0, 0, "Supported codecs"), IE(0x71, 5, 0, NF_MASK, "NAS message container (ciphered)"), IE(0x60, 4, 0, 0, "EPS bearer context status"),
    IE(0x6E, 4, 0, 0, "Requested extended DRX parameters"), IE(0x6A, 4, 0, NF_TIMER3, "T3324 value"), IE(0x67, 4, 0, NF_MASK, "UE radio capability ID"),
    IE(0x35, 4, 0, 0, "Requested mapped NSSAI"), IE(0x48, 4, 0, 0, "Additional information requested"), IE(0x1A, 4, 0, 0, "Requested WUS assistance information"),
    IE(0xA0, 1, 1, 0, "N5GC indication"), IE(0x30, 4, 0, 0, "Requested NB-N1 mode DRX parameters"), IE(0x29, 4, 0, 0, "UE request type"),
    IE(0x28, 4, 0, 0, "Paging restriction"), IE(0x72, 5, 0, NF_MASK, "Service-level-AA container"), IE(0x32, 4, 0, 0, "NID"),
    IE(0x16, 4, 0, 0, "MS determined PLMN with disaster condition"), IE(0x2A, 4, 0, 0, "Requested PEIPS assistance information"),
    IE(0x3B, 4, 0, NF_TIMER3, "Requested T3512 value"), END };
static const struct nas_ie k5_regacc[] = { IE(0, 11, 0, 0, "5GS registration result"), IE(0x77, 5, 0, NF_ID5G, "5G-GUTI"),
    IE(0x4A, 4, 0, 0, "Equivalent PLMNs"), IE(0x54, 4, 0, 0, "TAI list"), IE(0x15, 4, 0, NF_NSSAI, "Allowed NSSAI"),
    IE(0x11, 4, 0, 0, "Rejected NSSAI"), IE(0x31, 4, 0, NF_NSSAI, "Configured NSSAI"), IE(0x21, 4, 0, 0, "5GS network feature support"),
    IE(0x50, 4, 0, 0, "PDU session status"), IE(0x26, 4, 0, 0, "PDU session reactivation result"), IE(0x72, 5, 0, 0, "PDU session reactivation result error cause"),
    IE(0x79, 5, 0, 0, "LADN information"), IE(0xB0, 1, 1, 0, "MICO indication"), IE(0x90, 1, 1, 0, "Network slicing indication"),
    IE(0x27, 4, 0, 0, "Service area list"), IE(0x5E, 4, 0, NF_TIMER3, "T3512 value"), IE(0x5D, 4, 0, 0, "Non-3GPP de-registration timer value"),
    IE(0x16, 4, 0, 0, "T3502 value"), IE(0x34, 4, 0, 0, "Emergency number list"), IE(0x7A, 5, 0, 0, "Extended emergency number list"),
    IE(0x73, 5, 0, 0, "SOR transparent container"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0xA0, 1, 1, 0, "NSSAI inclusion mode"),
    IE(0x76, 5, 0, 0, "Operator-defined access category definitions"), IE(0x51, 4, 0, 0, "Negotiated DRX parameters"),
    IE(0xD0, 1, 1, 0, "Non-3GPP NW provided policies"), IE(0x60, 4, 0, 0, "EPS bearer context status"), IE(0x6E, 4, 0, 0, "Negotiated extended DRX parameters"),
    IE(0x6C, 4, 0, 0, "T3447 value"), IE(0x6B, 4, 0, 0, "T3448 value"), IE(0x6A, 4, 0, NF_TIMER3, "T3324 value"),
    IE(0x67, 4, 0, NF_MASK, "UE radio capability ID"), IE(0xE0, 1, 1, 0, "UE radio capability ID deletion indication"),
    IE(0x39, 4, 0, 0, "Pending NSSAI"), IE(0x74, 5, 0, 0, "Ciphering key data"), IE(0x75, 5, 0, NF_MASK, "CAG information list"),
    IE(0x1B, 4, 0, 0, "Truncated 5G-S-TMSI configuration"), IE(0x1C, 4, 0, 0, "Negotiated WUS assistance information"),
    IE(0x29, 4, 0, 0, "Negotiated NB-N1 mode DRX parameters"), IE(0x68, 4, 0, 0, "Extended rejected NSSAI"),
    IE(0x7B, 5, 0, 0, "Service-level-AA container"), IE(0x33, 4, 0, 0, "Negotiated PEIPS assistance information"),
    IE(0x35, 4, 0, 0, "5GS additional request result"), IE(0x70, 5, 0, 0, "NSSRG information"), IE(0x14, 4, 0, 0, "Disaster roaming wait range"),
    IE(0x2C, 4, 0, 0, "Disaster return wait range"), IE(0x13, 4, 0, 0, "List of PLMNs to be used in disaster condition"),
    IE(0x1D, 4, 0, 0, "Forbidden TAI(s) for the list of 5GS forbidden tracking areas for roaming"),
    IE(0x1E, 4, 0, 0, "Forbidden TAI(s) for the list of 5GS forbidden tracking areas for regional provision of service"),
    IE(0x71, 5, 0, 0, "Extended CAG information list"), IE(0x7C, 5, 0, 0, "NSAG information"), END };
static const struct nas_ie k5_regcmp[] = { IE(0x73, 5, 0, 0, "SOR transparent container"), END };
static const struct nas_ie k5_regrej[] = { IE(0, 10, 1, NF_CAUSE5MM, "5GMM cause"), IE(0x5F, 4, 0, NF_TIMER2, "T3346 value"),
    IE(0x16, 4, 0, NF_TIMER2, "T3502 value"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x69, 4, 0, 0, "Rejected NSSAI"), END };
static const struct nas_ie k5_deregul[] = { IE(0, 13, 1, 0, "ngKSI / De-registration type"), IE(0, 12, 0, NF_ID5G, "5GS mobile identity"), END };
static const struct nas_ie k5_deregdl[] = { IE(0, 13, 1, 0, "spare / De-registration type"), IE(0x58, 3, 2, NF_CAUSE5MM, "5GMM cause"),
    IE(0x5F, 4, 0, NF_TIMER2, "T3346 value"), IE(0x6D, 4, 0, 0, "Rejected NSSAI"), END };
static const struct nas_ie k5_none[] = { END };
static const struct nas_ie k5_srvreq[] = { IE(0, 13, 1, 0, "ngKSI / Service type"), IE(0, 12, 0, NF_ID5G, "5G-S-TMSI"),
    IE(0x40, 4, 0, 0, "Uplink data status"), IE(0x50, 4, 0, 0, "PDU session status"), IE(0x25, 4, 0, 0, "Allowed PDU session status"),
    IE(0x71, 5, 0, NF_MASK, "NAS message container (ciphered)"), END };
static const struct nas_ie k5_srvacc[] = { IE(0x50, 4, 0, 0, "PDU session status"), IE(0x26, 4, 0, 0, "PDU session reactivation result"),
    IE(0x72, 5, 0, 0, "PDU session reactivation result error cause"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x6B, 4, 0, 0, "T3448 value"),
    IE(0x34, 4, 0, 0, "5GS additional request result"), IE(0x1D, 4, 0, 0, "Forbidden TAI(s) (roaming)"), IE(0x1E, 4, 0, 0, "Forbidden TAI(s) (regional)"), END };
static const struct nas_ie k5_srvrej[] = { IE(0, 10, 1, NF_CAUSE5MM, "5GMM cause"), IE(0x50, 4, 0, 0, "PDU session status"),
    IE(0x5F, 4, 0, NF_TIMER2, "T3346 value"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x6B, 4, 0, 0, "T3448 value"), END };
static const struct nas_ie k5_cfgupd[] = { IE(0xD0, 1, 1, 0, "Configuration update indication"), IE(0x77, 5, 0, NF_ID5G, "5G-GUTI"),
    IE(0x54, 4, 0, 0, "TAI list"), IE(0x15, 4, 0, NF_NSSAI, "Allowed NSSAI"), IE(0x27, 4, 0, 0, "Service area list"),
    IE(0x43, 4, 0, 0, "Full name for network"), IE(0x45, 4, 0, 0, "Short name for network"), IE(0x46, 3, 2, 0, "Local time zone"),
    IE(0x47, 3, 8, 0, "Universal time and local time zone"), IE(0x49, 4, 0, 0, "Network daylight saving time"), IE(0x79, 5, 0, 0, "LADN information"),
    IE(0xB0, 1, 1, 0, "MICO indication"), IE(0x90, 1, 1, 0, "Network slicing indication"), IE(0x31, 4, 0, NF_NSSAI, "Configured NSSAI"),
    IE(0x11, 4, 0, 0, "Rejected NSSAI"), IE(0x76, 5, 0, 0, "Operator-defined access category definitions"), IE(0xF0, 1, 1, 0, "SMS indication"),
    IE(0x6C, 4, 0, 0, "T3447 value"), IE(0x75, 5, 0, NF_MASK, "CAG information list"), IE(0x67, 4, 0, NF_MASK, "UE radio capability ID"),
    IE(0xA0, 1, 1, 0, "UE radio capability ID deletion indication"), IE(0x44, 4, 0, 0, "5GS registration result"), IE(0x1B, 4, 0, 0, "Truncated 5G-S-TMSI configuration"),
    IE(0xC0, 1, 1, 0, "Additional configuration indication"), IE(0x68, 4, 0, 0, "Extended rejected NSSAI"), IE(0x72, 5, 0, 0, "Service-level-AA container"),
    IE(0x70, 5, 0, 0, "NSSRG information"), IE(0x14, 4, 0, 0, "Disaster roaming wait range"), IE(0x2C, 4, 0, 0, "Disaster return wait range"),
    IE(0x13, 4, 0, 0, "List of PLMNs to be used in disaster condition"), END };
static const struct nas_ie k5_authreq[] = { IE(0, 13, 1, 0, "spare / ngKSI"), IE(0, 11, 0, 0, "ABBA"), IE(0x21, 3, 17, NF_MASK, "Authentication parameter RAND"),
    IE(0x20, 4, 0, NF_MASK, "Authentication parameter AUTN"), IE(0x78, 5, 0, NF_MASK, "EAP message"), END };
static const struct nas_ie k5_authresp[] = { IE(0x2D, 4, 0, NF_MASK, "Authentication response parameter"), IE(0x78, 5, 0, NF_MASK, "EAP message"), END };
static const struct nas_ie k5_authfail[] = { IE(0, 10, 1, NF_CAUSE5MM, "5GMM cause"), IE(0x30, 4, 0, NF_MASK, "Authentication failure parameter"), END };
static const struct nas_ie k5_idreq[] = { IE(0, 13, 1, 0, "spare / Identity type"), END };
static const struct nas_ie k5_idresp[] = { IE(0, 12, 0, NF_ID5G, "Mobile identity"), END };
static const struct nas_ie k5_smc[] = { IE(0, 10, 1, 0, "Selected NAS security algorithms"), IE(0, 13, 1, 0, "ngKSI / spare"),
    IE(0, 11, 0, 0, "Replayed UE security capabilities"), IE(0xE0, 1, 1, 0, "IMEISV request"), IE(0x57, 3, 2, 0, "Selected EPS NAS security algorithms"),
    IE(0x36, 4, 0, 0, "Additional 5G security information"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x38, 4, 0, 0, "ABBA"),
    IE(0x19, 4, 0, 0, "Replayed S1 UE security capabilities"), END };
static const struct nas_ie k5_smcc[] = { IE(0x77, 5, 0, NF_ID5G, "IMEISV"), IE(0x71, 5, 0, NF_NASC5G, "NAS message container"),
    IE(0x78, 5, 0, NF_ID5G, "non-IMEISV PEI"), END };
static const struct nas_ie k5_smrej[] = { IE(0, 10, 1, NF_CAUSE5MM, "5GMM cause"), END };
static const struct nas_ie k5_status[] = { IE(0, 10, 1, NF_CAUSE5MM, "5GMM cause"), END };
static const struct nas_ie k5_ultr[] = { IE(0, 13, 1, NF_PCT, "spare / Payload container type"), IE(0, 12, 0, NF_PAYLOAD, "Payload container"),
    IE(0x12, 3, 2, NF_PSI, "PDU session ID"), IE(0x59, 3, 2, NF_PSI, "Old PDU session ID"), IE(0x80, 1, 1, 0, "Request type"),
    IE(0x22, 4, 0, NF_SNSSAI, "S-NSSAI"), IE(0x25, 4, 0, NF_DNN, "DNN"), IE(0x24, 4, 0, 0, "Additional information"),
    IE(0xA0, 1, 1, 0, "MA PDU session information"), IE(0xF0, 1, 1, 0, "Release assistance indication"), END };
static const struct nas_ie k5_dltr[] = { IE(0, 13, 1, NF_PCT, "spare / Payload container type"), IE(0, 12, 0, NF_PAYLOAD, "Payload container"),
    IE(0x12, 3, 2, NF_PSI, "PDU session ID"), IE(0x24, 4, 0, 0, "Additional information"), IE(0x58, 3, 2, NF_CAUSE5MM, "5GMM cause"),
    IE(0x37, 4, 0, NF_TIMER3, "Back-off timer value"), IE(0x3A, 4, 0, NF_TIMER3, "Lower bound timer value"), END };
static const struct nas_ie k5_notif[] = { IE(0, 13, 1, 0, "spare / Access type"), END };
/* ---- 5GSM ---- */
static const struct nas_ie k5s_estreq[] = { IE(0, 10, 2, 0, "Integrity protection maximum data rate"), IE(0x90, 1, 1, 0, "PDU session type"),
    IE(0xA0, 1, 1, 0, "SSC mode"), IE(0x28, 4, 0, 0, "5GSM capability"), IE(0x55, 3, 3, 0, "Maximum number of supported packet filters"),
    IE(0xB0, 1, 1, 0, "Always-on PDU session requested"), IE(0x39, 4, 0, NF_MASK, "SM PDU DN request container"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x66, 4, 0, 0, "IP header compression configuration"),
    IE(0x6F, 4, 0, 0, "DS-TT Ethernet port MAC address"), IE(0x6D, 4, 0, 0, "UE-DS-TT residence time"), IE(0x74, 5, 0, 0, "Port management information container"),
    IE(0x1F, 4, 0, 0, "Ethernet header compression configuration"), IE(0x29, 4, 0, NF_MASK, "Suggested interface identifier"),
    IE(0x70, 5, 0, NF_MASK, "Service-level-AA container"), IE(0x34, 4, 0, 0, "Requested MBS container"), IE(0x35, 4, 0, 0, "PDU session pair ID"),
    IE(0x36, 4, 0, 0, "RSN"), END };
static const struct nas_ie k5s_estacc[] = { IE(0, 13, 1, 0, "SSC mode / PDU session type"), IE(0, 12, 0, NF_MASK, "Authorized QoS rules"),
    IE(0, 11, 0, 0, "Session AMBR"), IE(0x59, 3, 2, NF_CAUSE5SM, "5GSM cause"), IE(0x29, 4, 0, NF_MASK, "PDU address"),
    IE(0x56, 3, 2, NF_TIMER2, "RQ timer value"), IE(0x22, 4, 0, NF_SNSSAI, "S-NSSAI"), IE(0x80, 1, 1, 0, "Always-on PDU session indication"),
    IE(0x75, 5, 0, NF_MASK, "Mapped EPS bearer contexts"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x79, 5, 0, 0, "Authorized QoS flow descriptions"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x25, 4, 0, NF_DNN, "DNN"), IE(0x17, 4, 0, 0, "5GSM network feature support"),
    IE(0x18, 4, 0, 0, "Serving PLMN rate control"), IE(0x77, 5, 0, 0, "ATSSS container"), IE(0xC0, 1, 1, 0, "Control plane only indication"),
    IE(0x66, 4, 0, 0, "IP header compression configuration"), IE(0x1F, 4, 0, 0, "Ethernet header compression configuration"),
    IE(0x70, 5, 0, NF_MASK, "Service-level-AA container"), IE(0x71, 5, 0, 0, "Received MBS container"), END };
static const struct nas_ie k5s_estrej[] = { IE(0, 10, 1, NF_CAUSE5SM, "5GSM cause"), IE(0x37, 4, 0, NF_TIMER3, "Back-off timer value"),
    IE(0xF0, 1, 1, 0, "Allowed SSC mode"), IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x61, 4, 0, 0, "5GSM congestion re-attempt indicator"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x1D, 4, 0, 0, "Re-attempt indicator"), IE(0x70, 5, 0, NF_MASK, "Service-level-AA container"), END };
static const struct nas_ie k5s_modreq[] = { IE(0x28, 4, 0, 0, "5GSM capability"), IE(0x59, 3, 2, NF_CAUSE5SM, "5GSM cause"),
    IE(0x55, 3, 3, 0, "Maximum number of supported packet filters"), IE(0xB0, 1, 1, 0, "Always-on PDU session requested"),
    IE(0x13, 4, 0, 0, "Integrity protection maximum data rate"), IE(0x7A, 5, 0, NF_MASK, "Requested QoS rules"),
    IE(0x79, 5, 0, 0, "Requested QoS flow descriptions"), IE(0x75, 5, 0, NF_MASK, "Mapped EPS bearer contexts"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x74, 5, 0, 0, "Port management information container"),
    IE(0x66, 4, 0, 0, "IP header compression configuration"), IE(0x1F, 4, 0, 0, "Ethernet header compression configuration"), END };
static const struct nas_ie k5s_modcmd[] = { IE(0x59, 3, 2, NF_CAUSE5SM, "5GSM cause"), IE(0x2A, 4, 0, 0, "Session AMBR"),
    IE(0x56, 3, 2, NF_TIMER2, "RQ timer value"), IE(0x80, 1, 1, 0, "Always-on PDU session indication"), IE(0x7A, 5, 0, NF_MASK, "Authorized QoS rules"),
    IE(0x75, 5, 0, NF_MASK, "Mapped EPS bearer contexts"), IE(0x79, 5, 0, 0, "Authorized QoS flow descriptions"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x77, 5, 0, 0, "ATSSS container"),
    IE(0x66, 4, 0, 0, "IP header compression configuration"), IE(0x74, 5, 0, 0, "Port management information container"),
    IE(0x1E, 4, 0, 0, "Serving PLMN rate control"), IE(0x1F, 4, 0, 0, "Ethernet header compression configuration"), END };
static const struct nas_ie k5s_modcmp[] = { IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x74, 5, 0, 0, "Port management information container"), END };
static const struct nas_ie k5s_modrej[] = { IE(0, 10, 1, NF_CAUSE5SM, "5GSM cause"), IE(0x37, 4, 0, NF_TIMER3, "Back-off timer value"),
    IE(0x61, 4, 0, 0, "5GSM congestion re-attempt indicator"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"),
    IE(0x1D, 4, 0, 0, "Re-attempt indicator"), END };
static const struct nas_ie k5s_relreq[] = { IE(0x59, 3, 2, NF_CAUSE5SM, "5GSM cause"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie k5s_relrej[] = { IE(0, 10, 1, NF_CAUSE5SM, "5GSM cause"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie k5s_relcmd[] = { IE(0, 10, 1, NF_CAUSE5SM, "5GSM cause"), IE(0x37, 4, 0, NF_TIMER3, "Back-off timer value"),
    IE(0x78, 5, 0, NF_MASK, "EAP message"), IE(0x61, 4, 0, 0, "5GSM congestion re-attempt indicator"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0xD0, 1, 1, 0, "Access type"), END };
static const struct nas_ie k5s_relcmp[] = { IE(0x59, 3, 2, NF_CAUSE5SM, "5GSM cause"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie k5s_status[] = { IE(0, 10, 1, NF_CAUSE5SM, "5GSM cause"), END };
/* ---- EMM ---- */
static const struct nas_ie ke_attreq[] = { IE(0, 13, 1, 0, "NAS key set identifier / EPS attach type"), IE(0, 11, 0, NF_IDEPS, "EPS mobile identity"),
    IE(0, 11, 0, 0, "UE network capability"), IE(0, 12, 0, NF_ESMC, "ESM message container"), IE(0x19, 3, 4, NF_MASK, "Old P-TMSI signature"),
    IE(0x50, 4, 0, NF_IDEPS, "Additional GUTI"), IE(0x52, 3, 6, 0, "Last visited registered TAI"), IE(0x5C, 3, 3, 0, "DRX parameter"),
    IE(0x31, 4, 0, 0, "MS network capability"), IE(0x13, 3, 6, 0, "Old location area identification"), IE(0x90, 1, 1, 0, "TMSI status"),
    IE(0x11, 4, 0, 0, "Mobile station classmark 2"), IE(0x20, 4, 0, 0, "Mobile station classmark 3"), IE(0x40, 4, 0, 0, "Supported codecs"),
    IE(0xF0, 1, 1, 0, "Additional update type"), IE(0x5D, 4, 0, 0, "Voice domain preference and UE's usage setting"), IE(0xD0, 1, 1, 0, "Device properties"),
    IE(0xE0, 1, 1, 0, "Old GUTI type"), IE(0xC0, 1, 1, 0, "MS network feature support"), IE(0x10, 4, 0, 0, "TMSI based NRI container"),
    IE(0x6A, 4, 0, NF_TIMER3, "T3324 value"), IE(0x5E, 4, 0, NF_TIMER3, "T3412 extended value"), IE(0x6E, 4, 0, 0, "Extended DRX parameters"),
    IE(0x6F, 4, 0, 0, "UE additional security capability"), IE(0x6D, 4, 0, 0, "UE status"), IE(0x17, 3, 2, 0, "Additional information requested"),
    IE(0x32, 4, 0, 0, "N1 UE network capability"), IE(0x34, 4, 0, NF_MASK, "UE radio capability ID availability"),
    IE(0x35, 4, 0, 0, "Requested WUS assistance information"), IE(0x36, 4, 0, 0, "DRX parameter in NB-S1 mode"), END };
static const struct nas_ie ke_attacc[] = { IE(0, 13, 1, 0, "spare / EPS attach result"), IE(0, 10, 1, NF_TIMER2, "T3412 value"),
    IE(0, 11, 0, 0, "TAI list"), IE(0, 12, 0, NF_ESMC, "ESM message container"), IE(0x50, 4, 0, NF_IDEPS, "GUTI"),
    IE(0x13, 3, 6, 0, "Location area identification"), IE(0x23, 4, 0, NF_IDEPS, "MS identity"), IE(0x53, 3, 2, NF_CAUSEEMM, "EMM cause"),
    IE(0x17, 3, 2, NF_TIMER2, "T3402 value"), IE(0x59, 3, 2, NF_TIMER2, "T3423 value"), IE(0x4A, 4, 0, 0, "Equivalent PLMNs"),
    IE(0x34, 4, 0, 0, "Emergency number list"), IE(0x64, 4, 0, 0, "EPS network feature support"), IE(0xF0, 1, 1, 0, "Additional update result"),
    IE(0x5E, 4, 0, NF_TIMER3, "T3412 extended value"), IE(0x6A, 4, 0, NF_TIMER3, "T3324 value"), IE(0x6E, 4, 0, 0, "Extended DRX parameters"),
    IE(0x65, 4, 0, 0, "DCN-ID"), IE(0xE0, 1, 1, 0, "SMS services status"), IE(0xD0, 1, 1, 0, "Non-3GPP NW provided policies"),
    IE(0x6B, 4, 0, 0, "T3448 value"), IE(0xC0, 1, 1, 0, "Network policy"), IE(0x6C, 4, 0, 0, "T3447 value"),
    IE(0x7A, 5, 0, 0, "Extended emergency number list"), IE(0x7C, 5, 0, NF_MASK, "Ciphering key data"), IE(0x66, 4, 0, NF_MASK, "UE radio capability ID"),
    IE(0xB0, 1, 1, 0, "UE radio capability ID deletion indication"), IE(0x35, 4, 0, 0, "Negotiated WUS assistance information"),
    IE(0x36, 4, 0, 0, "Negotiated DRX parameter in NB-S1 mode"), END };
static const struct nas_ie ke_esmonly[] = { IE(0, 12, 0, NF_ESMC, "ESM message container"), END };
static const struct nas_ie ke_cause[] = { IE(0, 10, 1, NF_CAUSEEMM, "EMM cause"), IE(0x78, 5, 0, NF_ESMC, "ESM message container"),
    IE(0x5F, 4, 0, NF_TIMER2, "T3346 value"), IE(0x16, 4, 0, NF_TIMER2, "T3402 value"), IE(0xA0, 1, 1, 0, "Extended EMM cause"), END };
static const struct nas_ie ke_detul[] = { IE(0, 13, 1, 0, "NAS key set identifier / Detach type"), IE(0, 11, 0, NF_IDEPS, "EPS mobile identity"), END };
static const struct nas_ie ke_detdl[] = { IE(0, 13, 1, 0, "spare / Detach type"), IE(0x53, 3, 2, NF_CAUSEEMM, "EMM cause"), END };
static const struct nas_ie ke_none[] = { END };
static const struct nas_ie ke_taureq[] = { IE(0, 13, 1, 0, "NAS key set identifier / EPS update type"), IE(0, 11, 0, NF_IDEPS, "Old GUTI"),
    IE(0xB0, 1, 1, 0, "Non-current native NAS key set identifier"), IE(0x80, 1, 1, 0, "GPRS ciphering key sequence number"),
    IE(0x19, 3, 4, NF_MASK, "Old P-TMSI signature"), IE(0x50, 4, 0, NF_IDEPS, "Additional GUTI"), IE(0x55, 3, 5, NF_MASK, "NonceUE"),
    IE(0x58, 4, 0, 0, "UE network capability"), IE(0x52, 3, 6, 0, "Last visited registered TAI"), IE(0x5C, 3, 3, 0, "DRX parameter"),
    IE(0xA0, 1, 1, 0, "UE radio capability information update needed"), IE(0x57, 4, 0, 0, "EPS bearer context status"),
    IE(0x31, 4, 0, 0, "MS network capability"), IE(0x13, 3, 6, 0, "Old location area identification"), IE(0x90, 1, 1, 0, "TMSI status"),
    IE(0x11, 4, 0, 0, "Mobile station classmark 2"), IE(0x20, 4, 0, 0, "Mobile station classmark 3"), IE(0x40, 4, 0, 0, "Supported codecs"),
    IE(0xF0, 1, 1, 0, "Additional update type"), IE(0x5D, 4, 0, 0, "Voice domain preference and UE's usage setting"), IE(0xE0, 1, 1, 0, "Old GUTI type"),
    IE(0xD0, 1, 1, 0, "Device properties"), IE(0xC0, 1, 1, 0, "MS network feature support"), IE(0x10, 4, 0, 0, "TMSI based NRI container"),
    IE(0x6A, 4, 0, NF_TIMER3, "T3324 value"), IE(0x5E, 4, 0, NF_TIMER3, "T3412 extended value"), IE(0x6E, 4, 0, 0, "Extended DRX parameters"),
    IE(0x6F, 4, 0, 0, "UE additional security capability"), IE(0x6D, 4, 0, 0, "UE status"), IE(0x17, 3, 2, 0, "Additional information requested"),
    IE(0x32, 4, 0, 0, "N1 UE network capability"), IE(0x34, 4, 0, NF_MASK, "UE radio capability ID availability"),
    IE(0x35, 4, 0, 0, "Requested WUS assistance information"), IE(0x36, 4, 0, 0, "DRX parameter in NB-S1 mode"), END };
static const struct nas_ie ke_tauacc[] = { IE(0, 13, 1, 0, "spare / EPS update result"), IE(0x5A, 3, 2, NF_TIMER2, "T3412 value"),
    IE(0x50, 4, 0, NF_IDEPS, "GUTI"), IE(0x54, 4, 0, 0, "TAI list"), IE(0x57, 4, 0, 0, "EPS bearer context status"),
    IE(0x13, 3, 6, 0, "Location area identification"), IE(0x23, 4, 0, NF_IDEPS, "MS identity"), IE(0x53, 3, 2, NF_CAUSEEMM, "EMM cause"),
    IE(0x17, 3, 2, NF_TIMER2, "T3402 value"), IE(0x59, 3, 2, NF_TIMER2, "T3423 value"), IE(0x4A, 4, 0, 0, "Equivalent PLMNs"),
    IE(0x34, 4, 0, 0, "Emergency number list"), IE(0x64, 4, 0, 0, "EPS network feature support"), IE(0xF0, 1, 1, 0, "Additional update result"),
    IE(0x5E, 4, 0, NF_TIMER3, "T3412 extended value"), IE(0x6A, 4, 0, NF_TIMER3, "T3324 value"), IE(0x6E, 4, 0, 0, "Extended DRX parameters"),
    IE(0x68, 4, 0, 0, "Header compression configuration status"), IE(0x65, 4, 0, 0, "DCN-ID"), IE(0xE0, 1, 1, 0, "SMS services status"),
    IE(0xD0, 1, 1, 0, "Non-3GPP NW policies"), IE(0x6B, 4, 0, 0, "T3448 value"), IE(0xC0, 1, 1, 0, "Network policy"),
    IE(0x6C, 4, 0, 0, "T3447 value"), IE(0x7A, 5, 0, 0, "Extended emergency number list"), IE(0x7C, 5, 0, NF_MASK, "Ciphering key data"),
    IE(0x66, 4, 0, NF_MASK, "UE radio capability ID"), IE(0xB0, 1, 1, 0, "UE radio capability ID deletion indication"),
    IE(0x35, 4, 0, 0, "Negotiated WUS assistance information"), IE(0x36, 4, 0, 0, "Negotiated DRX parameter in NB-S1 mode"), END };
static const struct nas_ie ke_extsr[] = { IE(0, 13, 1, 0, "NAS key set identifier / Service type"), IE(0, 11, 0, NF_IDEPS, "M-TMSI"),
    IE(0xB0, 1, 1, 0, "CSFB response"), IE(0x57, 4, 0, 0, "EPS bearer context status"), IE(0xD0, 1, 1, 0, "Device properties"),
    IE(0x29, 4, 0, 0, "UE request type"), IE(0x28, 4, 0, 0, "Paging restriction"), END };
static const struct nas_ie ke_srvrej[] = { IE(0, 10, 1, NF_CAUSEEMM, "EMM cause"), IE(0x5B, 3, 2, NF_TIMER2, "T3442 value"),
    IE(0x5F, 4, 0, NF_TIMER2, "T3346 value"), IE(0x6B, 4, 0, 0, "T3448 value"), END };
static const struct nas_ie ke_authreq[] = { IE(0, 13, 1, 0, "spare / NAS key set identifier"), IE(0, 10, 16, NF_MASK, "Authentication parameter RAND"),
    IE(0, 11, 0, NF_MASK, "Authentication parameter AUTN"), END };
static const struct nas_ie ke_authresp[] = { IE(0, 11, 0, NF_MASK, "Authentication response parameter"), END };
static const struct nas_ie ke_authfail[] = { IE(0, 10, 1, NF_CAUSEEMM, "EMM cause"), IE(0x30, 4, 0, NF_MASK, "Authentication failure parameter"), END };
static const struct nas_ie ke_idreq[] = { IE(0, 13, 1, 0, "spare / Identity type 2"), END };
static const struct nas_ie ke_idresp[] = { IE(0, 11, 0, NF_IDEPS, "Mobile identity"), END };
static const struct nas_ie ke_smc[] = { IE(0, 10, 1, 0, "Selected NAS security algorithms"), IE(0, 13, 1, 0, "spare / NAS key set identifier"),
    IE(0, 11, 0, 0, "Replayed UE security capabilities"), IE(0xC0, 1, 1, 0, "IMEISV request"), IE(0x55, 3, 5, NF_MASK, "Replayed nonceUE"),
    IE(0x56, 3, 5, NF_MASK, "NonceMME"), IE(0x4F, 4, 0, 0, "HashMME"), IE(0x6F, 4, 0, 0, "Replayed UE additional security capability"),
    IE(0x37, 4, 0, 0, "UE radio capability ID request"), END };
static const struct nas_ie ke_smcc[] = { IE(0x23, 4, 0, NF_IDEPS, "IMEISV"), IE(0x79, 5, 0, NF_MASK, "Replayed NAS message container"),
    IE(0x66, 4, 0, NF_MASK, "UE radio capability ID"), END };
static const struct nas_ie ke_info[] = { IE(0x43, 4, 0, 0, "Full name for network"), IE(0x45, 4, 0, 0, "Short name for network"),
    IE(0x46, 3, 2, 0, "Local time zone"), IE(0x47, 3, 8, 0, "Universal time and local time zone"), IE(0x49, 4, 0, 0, "Network daylight saving time"), END };
static const struct nas_ie ke_nastr[] = { IE(0, 11, 0, NF_MASK, "NAS message container"), END };
static const struct nas_ie ke_gentr[] = { IE(0, 10, 1, 0, "Generic message container type"), IE(0, 12, 0, NF_MASK, "Generic message container"),
    IE(0x65, 4, 0, 0, "Additional information"), END };
static const struct nas_ie ke_cssn[] = { IE(0, 10, 1, 0, "Paging identity"), IE(0x60, 4, 0, NF_MASK, "CLI"), IE(0x61, 3, 2, 0, "SS Code"),
    IE(0x62, 3, 2, 0, "LCS indicator"), IE(0x63, 4, 0, NF_MASK, "LCS client identity"), END };
static const struct nas_ie ke_gutirc[] = { IE(0, 11, 0, NF_IDEPS, "GUTI"), IE(0x54, 4, 0, 0, "TAI list"), IE(0x65, 4, 0, 0, "DCN-ID"),
    IE(0x66, 4, 0, NF_MASK, "UE radio capability ID"), IE(0xB0, 1, 1, 0, "UE radio capability ID deletion indication"), END };
/* ---- ESM ---- */
static const struct nas_ie ks_actdef[] = { IE(0, 11, 0, NF_QCI, "EPS quality of service"), IE(0, 11, 0, NF_DNN, "Access point name"),
    IE(0, 11, 0, NF_MASK, "PDN address"), IE(0x5D, 4, 0, 0, "Transaction identifier"), IE(0x30, 4, 0, 0, "Negotiated QoS"),
    IE(0x32, 3, 2, 0, "Negotiated LLC SAPI"), IE(0x80, 1, 1, 0, "Radio priority"), IE(0x34, 4, 0, 0, "Packet flow identifier"),
    IE(0x5E, 4, 0, 0, "APN-AMBR"), IE(0x58, 3, 2, NF_CAUSEESM, "ESM cause"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0xB0, 1, 1, 0, "Connectivity type"), IE(0xC0, 1, 1, 0, "WLAN offload indication"), IE(0x33, 4, 0, 0, "NBIFOM container"),
    IE(0x66, 4, 0, 0, "Header compression configuration"), IE(0x90, 1, 1, 0, "Control plane only indication"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x6E, 4, 0, 0, "Serving PLMN rate control"),
    IE(0x5F, 4, 0, 0, "Extended APN-AMBR"), IE(0x5C, 4, 0, 0, "Extended EPS QoS"), END };
static const struct nas_ie ks_actded[] = { IE(0, 13, 1, 0, "spare / Linked EPS bearer identity"), IE(0, 11, 0, NF_QCI, "EPS quality of service"),
    IE(0, 11, 0, NF_MASK, "Traffic flow template"), IE(0x5D, 4, 0, 0, "Transaction identifier"), IE(0x30, 4, 0, 0, "Negotiated QoS"),
    IE(0x32, 3, 2, 0, "Negotiated LLC SAPI"), IE(0x80, 1, 1, 0, "Radio priority"), IE(0x34, 4, 0, 0, "Packet flow identifier"),
    IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"), IE(0xC0, 1, 1, 0, "WLAN offload indication"), IE(0x33, 4, 0, 0, "NBIFOM container"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x5C, 4, 0, 0, "Extended EPS QoS"), END };
static const struct nas_ie ks_acc[] = { IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"), IE(0x33, 4, 0, 0, "NBIFOM container"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_rej[] = { IE(0, 10, 1, NF_CAUSEESM, "ESM cause"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0x33, 4, 0, 0, "NBIFOM container"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_modreq[] = { IE(0x5B, 4, 0, NF_QCI, "New EPS QoS"), IE(0x36, 4, 0, NF_MASK, "TFT"), IE(0x30, 4, 0, 0, "New QoS"),
    IE(0x32, 3, 2, 0, "Negotiated LLC SAPI"), IE(0x80, 1, 1, 0, "Radio priority"), IE(0x34, 4, 0, 0, "Packet flow identifier"),
    IE(0x5E, 4, 0, 0, "APN-AMBR"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"), IE(0xC0, 1, 1, 0, "WLAN offload indication"),
    IE(0x33, 4, 0, 0, "NBIFOM container"), IE(0x66, 4, 0, 0, "Header compression configuration"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), IE(0x5F, 4, 0, 0, "Extended APN-AMBR"), IE(0x5C, 4, 0, 0, "Extended EPS QoS"), END };
static const struct nas_ie ks_deact[] = { IE(0, 10, 1, NF_CAUSEESM, "ESM cause"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0x37, 4, 0, NF_TIMER3, "T3396 value"), IE(0xC0, 1, 1, 0, "WLAN offload indication"), IE(0x33, 4, 0, 0, "NBIFOM container"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_pdnreq[] = { IE(0, 13, 1, 0, "PDN type / Request type"), IE(0xD0, 1, 1, 0, "ESM information transfer flag"),
    IE(0x28, 4, 0, NF_DNN, "Access point name"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"), IE(0xC0, 1, 1, 0, "Device properties"),
    IE(0x33, 4, 0, 0, "NBIFOM container"), IE(0x66, 4, 0, 0, "Header compression configuration"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_esminfo[] = { IE(0x28, 4, 0, NF_DNN, "Access point name"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_status[] = { IE(0, 10, 1, NF_CAUSEESM, "ESM cause"), END };
static const struct nas_ie ks_bresreq[] = { IE(0, 13, 1, 0, "spare / Linked EPS bearer identity"), IE(0, 11, 0, NF_MASK, "Traffic flow aggregate"),
    IE(0, 11, 0, NF_QCI, "Required traffic flow QoS"), IE(0x58, 3, 2, NF_CAUSEESM, "ESM cause"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0xC0, 1, 1, 0, "Device properties"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_bresmod[] = { IE(0, 13, 1, 0, "spare / EPS bearer identity for packet filter"), IE(0, 11, 0, NF_MASK, "Traffic flow aggregate"),
    IE(0x5B, 4, 0, NF_QCI, "Required traffic flow QoS"), IE(0x58, 3, 2, NF_CAUSEESM, "ESM cause"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0xC0, 1, 1, 0, "Device properties"), IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };
static const struct nas_ie ks_notif[] = { IE(0, 11, 0, 0, "Notification indicator"), END };
static const struct nas_ie ks_pdndisc[] = { IE(0, 13, 1, 0, "spare / Linked EPS bearer identity"), IE(0x27, 4, 0, NF_MASK, "Protocol configuration options"),
    IE(0x7B, 5, 0, NF_MASK, "Extended protocol configuration options"), END };

static const struct nas_msg k_nas_msgs[] = {
    { 0x7E, 0x41, 0, "Registration request", k5_regreq }, { 0x7E, 0x42, 0, "Registration accept", k5_regacc },
    { 0x7E, 0x43, 0, "Registration complete", k5_regcmp }, { 0x7E, 0x44, 0, "Registration reject", k5_regrej },
    { 0x7E, 0x45, 0, "Deregistration request (UE originating)", k5_deregul }, { 0x7E, 0x46, 0, "Deregistration accept (UE originating)", k5_none },
    { 0x7E, 0x47, 0, "Deregistration request (UE terminated)", k5_deregdl }, { 0x7E, 0x48, 0, "Deregistration accept (UE terminated)", k5_none },
    { 0x7E, 0x4C, 0, "Service request", k5_srvreq }, { 0x7E, 0x4D, 0, "Service reject", k5_srvrej }, { 0x7E, 0x4E, 0, "Service accept", k5_srvacc },
    { 0x7E, 0x4F, 0, "Control plane service request", NULL }, { 0x7E, 0x50, 0, "Network slice-specific authentication command", NULL },
    { 0x7E, 0x51, 0, "Network slice-specific authentication complete", NULL }, { 0x7E, 0x52, 0, "Network slice-specific authentication result", NULL },
    { 0x7E, 0x54, 0, "Configuration update command", k5_cfgupd }, { 0x7E, 0x55, 0, "Configuration update complete", k5_none },
    { 0x7E, 0x56, 0, "Authentication request", k5_authreq }, { 0x7E, 0x57, 0, "Authentication response", k5_authresp },
    { 0x7E, 0x58, 0, "Authentication reject", NULL }, { 0x7E, 0x59, 0, "Authentication failure", k5_authfail },
    { 0x7E, 0x5A, 0, "Authentication result", NULL }, { 0x7E, 0x5B, 0, "Identity request", k5_idreq }, { 0x7E, 0x5C, 0, "Identity response", k5_idresp },
    { 0x7E, 0x5D, 0, "Security mode command", k5_smc }, { 0x7E, 0x5E, 0, "Security mode complete", k5_smcc }, { 0x7E, 0x5F, 0, "Security mode reject", k5_smrej },
    { 0x7E, 0x64, 0, "5GMM status", k5_status }, { 0x7E, 0x65, 0, "Notification", k5_notif }, { 0x7E, 0x66, 0, "Notification response", NULL },
    { 0x7E, 0x67, 0, "UL NAS transport", k5_ultr }, { 0x7E, 0x68, 0, "DL NAS transport", k5_dltr },
    { 0x7E, 0x69, 0, "Relay key request", NULL }, { 0x7E, 0x6A, 0, "Relay key accept", NULL }, { 0x7E, 0x6B, 0, "Relay key reject", NULL },
    { 0x7E, 0x6C, 0, "Relay authentication request", NULL }, { 0x7E, 0x6D, 0, "Relay authentication response", NULL },
    { 0x2E, 0xC1, 0, "PDU session establishment request", k5s_estreq }, { 0x2E, 0xC2, 0, "PDU session establishment accept", k5s_estacc },
    { 0x2E, 0xC3, 0, "PDU session establishment reject", k5s_estrej }, { 0x2E, 0xC5, 0, "PDU session authentication command", NULL },
    { 0x2E, 0xC6, 0, "PDU session authentication complete", NULL }, { 0x2E, 0xC7, 0, "PDU session authentication result", NULL },
    { 0x2E, 0xC9, 0, "PDU session modification request", k5s_modreq }, { 0x2E, 0xCA, 0, "PDU session modification reject", k5s_modrej },
    { 0x2E, 0xCB, 0, "PDU session modification command", k5s_modcmd }, { 0x2E, 0xCC, 0, "PDU session modification complete", k5s_modcmp },
    { 0x2E, 0xCD, 0, "PDU session modification command reject", k5s_status }, { 0x2E, 0xD1, 0, "PDU session release request", k5s_relreq },
    { 0x2E, 0xD2, 0, "PDU session release reject", k5s_relrej }, { 0x2E, 0xD3, 0, "PDU session release command", k5s_relcmd },
    { 0x2E, 0xD4, 0, "PDU session release complete", k5s_relcmp }, { 0x2E, 0xD6, 0, "5GSM status", k5s_status },
    { 0x2E, 0xD8, 0, "Service-level authentication command", NULL }, { 0x2E, 0xD9, 0, "Service-level authentication complete", NULL },
    { 0x2E, 0xDA, 0, "Remote UE report", NULL }, { 0x2E, 0xDB, 0, "Remote UE report response", NULL },
    { 0x07, 0x41, 0, "Attach request", ke_attreq }, { 0x07, 0x42, 0, "Attach accept", ke_attacc }, { 0x07, 0x43, 0, "Attach complete", ke_esmonly },
    { 0x07, 0x44, 0, "Attach reject", ke_cause }, { 0x07, 0x45, 1, "Detach request", ke_detul }, { 0x07, 0x45, 2, "Detach request", ke_detdl },
    { 0x07, 0x46, 0, "Detach accept", ke_none }, { 0x07, 0x48, 0, "Tracking area update request", ke_taureq },
    { 0x07, 0x49, 0, "Tracking area update accept", ke_tauacc }, { 0x07, 0x4A, 0, "Tracking area update complete", ke_none },
    { 0x07, 0x4B, 0, "Tracking area update reject", ke_cause }, { 0x07, 0x4C, 0, "Extended service request", ke_extsr },
    { 0x07, 0x4D, 0, "Control plane service request", NULL }, { 0x07, 0x4E, 0, "Service reject", ke_srvrej }, { 0x07, 0x4F, 0, "Service accept", NULL },
    { 0x07, 0x50, 0, "GUTI reallocation command", ke_gutirc }, { 0x07, 0x51, 0, "GUTI reallocation complete", ke_none },
    { 0x07, 0x52, 0, "Authentication request", ke_authreq }, { 0x07, 0x53, 0, "Authentication response", ke_authresp },
    { 0x07, 0x54, 0, "Authentication reject", ke_none }, { 0x07, 0x5C, 0, "Authentication failure", ke_authfail },
    { 0x07, 0x55, 0, "Identity request", ke_idreq }, { 0x07, 0x56, 0, "Identity response", ke_idresp },
    { 0x07, 0x5D, 0, "Security mode command", ke_smc }, { 0x07, 0x5E, 0, "Security mode complete", ke_smcc },
    { 0x07, 0x5F, 0, "Security mode reject", ke_cause }, { 0x07, 0x60, 0, "EMM status", ke_cause }, { 0x07, 0x61, 0, "EMM information", ke_info },
    { 0x07, 0x62, 0, "Downlink NAS transport", ke_nastr }, { 0x07, 0x63, 0, "Uplink NAS transport", ke_nastr },
    { 0x07, 0x64, 0, "CS service notification", ke_cssn }, { 0x07, 0x68, 0, "Downlink generic NAS transport", ke_gentr },
    { 0x07, 0x69, 0, "Uplink generic NAS transport", ke_gentr },
    { 0x02, 0xC1, 0, "Activate default EPS bearer context request", ks_actdef }, { 0x02, 0xC2, 0, "Activate default EPS bearer context accept", ks_acc },
    { 0x02, 0xC3, 0, "Activate default EPS bearer context reject", ks_rej }, { 0x02, 0xC5, 0, "Activate dedicated EPS bearer context request", ks_actded },
    { 0x02, 0xC6, 0, "Activate dedicated EPS bearer context accept", ks_acc }, { 0x02, 0xC7, 0, "Activate dedicated EPS bearer context reject", ks_rej },
    { 0x02, 0xC9, 0, "Modify EPS bearer context request", ks_modreq }, { 0x02, 0xCA, 0, "Modify EPS bearer context accept", ks_acc },
    { 0x02, 0xCB, 0, "Modify EPS bearer context reject", ks_rej }, { 0x02, 0xCD, 0, "Deactivate EPS bearer context request", ks_deact },
    { 0x02, 0xCE, 0, "Deactivate EPS bearer context accept", ks_acc }, { 0x02, 0xD0, 0, "PDN connectivity request", ks_pdnreq },
    { 0x02, 0xD1, 0, "PDN connectivity reject", ks_rej }, { 0x02, 0xD2, 0, "PDN disconnect request", ks_pdndisc },
    { 0x02, 0xD3, 0, "PDN disconnect reject", ks_rej }, { 0x02, 0xD4, 0, "Bearer resource allocation request", ks_bresreq },
    { 0x02, 0xD5, 0, "Bearer resource allocation reject", ks_rej }, { 0x02, 0xD6, 0, "Bearer resource modification request", ks_bresmod },
    { 0x02, 0xD7, 0, "Bearer resource modification reject", ks_rej }, { 0x02, 0xD9, 0, "ESM information request", ke_none },
    { 0x02, 0xDA, 0, "ESM information response", ks_esminfo }, { 0x02, 0xDB, 0, "Notification", ks_notif },
    { 0x02, 0xDC, 0, "ESM dummy message", NULL }, { 0x02, 0xE8, 0, "ESM status", ks_status },
    { 0, 0, 0, NULL, NULL } };

static const char *nas_cause_name(int kind, int c) {
    static const struct { uint8_t k, c; const char *n; } t[] = {
        {1,3,"Illegal UE"},{1,5,"PEI not accepted"},{1,6,"Illegal ME"},{1,7,"5GS services not allowed"},{1,9,"UE identity cannot be derived by the network"},
        {1,10,"Implicitly de-registered"},{1,11,"PLMN not allowed"},{1,12,"Tracking area not allowed"},{1,13,"Roaming not allowed in this tracking area"},
        {1,15,"No suitable cells in tracking area"},{1,20,"MAC failure"},{1,21,"Synch failure"},{1,22,"Congestion"},{1,23,"UE security capabilities mismatch"},
        {1,24,"Security mode rejected, unspecified"},{1,26,"Non-5G authentication unacceptable"},{1,27,"N1 mode not allowed"},{1,28,"Restricted service area"},
        {1,31,"Redirection to EPC required"},{1,43,"LADN not available"},{1,62,"No network slices available"},{1,65,"Maximum number of PDU sessions reached"},
        {1,67,"Insufficient resources for specific slice and DNN"},{1,69,"Insufficient resources for specific slice"},{1,71,"ngKSI already in use"},
        {1,72,"Non-3GPP access to 5GCN not allowed"},{1,73,"Serving network not authorized"},{1,90,"Payload was not forwarded"},{1,91,"DNN not supported or not subscribed in the slice"},
        {1,92,"Insufficient user-plane resources for the PDU session"},{1,95,"Semantically incorrect message"},{1,96,"Invalid mandatory information"},
        {1,97,"Message type non-existent or not implemented"},{1,98,"Message type not compatible with the protocol state"},{1,99,"Information element non-existent or not implemented"},
        {1,100,"Conditional IE error"},{1,101,"Message not compatible with the protocol state"},{1,111,"Protocol error, unspecified"},
        {2,8,"Operator determined barring"},{2,26,"Insufficient resources"},{2,27,"Missing or unknown DNN"},{2,28,"Unknown PDU session type"},
        {2,29,"User authentication or authorization failed"},{2,31,"Request rejected, unspecified"},{2,32,"Service option not supported"},
        {2,33,"Requested service option not subscribed"},{2,35,"PTI already in use"},{2,36,"Regular deactivation"},{2,37,"5GS QoS not accepted"},
        {2,38,"Network failure"},{2,39,"Reactivation requested"},{2,41,"Semantic error in the TFT operation"},{2,42,"Syntactical error in the TFT operation"},
        {2,43,"Invalid PDU session identity"},{2,44,"Semantic errors in packet filter(s)"},{2,45,"Syntactical error in packet filter(s)"},
        {2,46,"Out of LADN service area"},{2,47,"PTI mismatch"},{2,50,"PDU session type IPv4 only allowed"},{2,51,"PDU session type IPv6 only allowed"},
        {2,54,"PDU session does not exist"},{2,57,"PDU session type IPv4v6 only allowed"},{2,58,"PDU session type Unstructured only allowed"},
        {2,59,"Unsupported 5QI value"},{2,61,"PDU session type Ethernet only allowed"},{2,67,"Insufficient resources for specific slice and DNN"},
        {2,68,"Not supported SSC mode"},{2,69,"Insufficient resources for specific slice"},{2,70,"Missing or unknown DNN in a slice"},
        {2,81,"Invalid PTI value"},{2,82,"Maximum data rate per UE for user-plane integrity protection is too low"},{2,83,"Semantic error in the QoS operation"},
        {2,84,"Syntactical error in the QoS operation"},{2,85,"Invalid mapped EPS bearer identity"},{2,95,"Semantically incorrect message"},
        {2,96,"Invalid mandatory information"},{2,97,"Message type non-existent or not implemented"},{2,111,"Protocol error, unspecified"},
        {3,2,"IMSI unknown in HSS"},{3,3,"Illegal UE"},{3,5,"IMEI not accepted"},{3,6,"Illegal ME"},{3,7,"EPS services not allowed"},
        {3,8,"EPS services and non-EPS services not allowed"},{3,9,"UE identity cannot be derived by the network"},{3,10,"Implicitly detached"},
        {3,11,"PLMN not allowed"},{3,12,"Tracking area not allowed"},{3,13,"Roaming not allowed in this tracking area"},{3,14,"EPS services not allowed in this PLMN"},
        {3,15,"No suitable cells in tracking area"},{3,16,"MSC temporarily not reachable"},{3,17,"Network failure"},{3,18,"CS domain not available"},
        {3,19,"ESM failure"},{3,20,"MAC failure"},{3,21,"Synch failure"},{3,22,"Congestion"},{3,23,"UE security capabilities mismatch"},
        {3,24,"Security mode rejected, unspecified"},{3,25,"Not authorized for this CSG"},{3,26,"Non-EPS authentication unacceptable"},
        {3,31,"Redirection to 5GCN required"},{3,35,"Requested service option not authorized in this PLMN"},{3,39,"CS service temporarily not available"},
        {3,40,"No EPS bearer context activated"},{3,42,"Severe network failure"},{3,95,"Semantically incorrect message"},{3,96,"Invalid mandatory information"},
        {3,97,"Message type non-existent or not implemented"},{3,111,"Protocol error, unspecified"},
        {4,8,"Operator determined barring"},{4,26,"Insufficient resources"},{4,27,"Missing or unknown APN"},{4,28,"Unknown PDN type"},
        {4,29,"User authentication failed"},{4,30,"Request rejected by Serving GW or PDN GW"},{4,31,"Request rejected, unspecified"},
        {4,32,"Service option not supported"},{4,33,"Requested service option not subscribed"},{4,34,"Service option temporarily out of order"},
        {4,35,"PTI already in use"},{4,36,"Regular deactivation"},{4,37,"EPS QoS not accepted"},{4,38,"Network failure"},{4,39,"Reactivation requested"},
        {4,41,"Semantic error in the TFT operation"},{4,42,"Syntactical error in the TFT operation"},{4,43,"Invalid EPS bearer identity"},
        {4,44,"Semantic errors in packet filter(s)"},{4,45,"Syntactical errors in packet filter(s)"},{4,47,"PTI mismatch"},
        {4,49,"Last PDN disconnection not allowed"},{4,50,"PDN type IPv4 only allowed"},{4,51,"PDN type IPv6 only allowed"},
        {4,54,"PDN connection does not exist"},{4,55,"Multiple PDN connections for a given APN not allowed"},{4,56,"Collision with network initiated request"},
        {4,57,"PDN type IPv4v6 only allowed"},{4,59,"Unsupported QCI value"},{4,60,"Bearer handling not supported"},{4,65,"Maximum number of EPS bearers reached"},
        {4,66,"Requested APN not supported in current RAT and PLMN combination"},{4,81,"Invalid PTI value"},{4,95,"Semantically incorrect message"},
        {4,96,"Invalid mandatory information"},{4,111,"Protocol error, unspecified"},{4,112,"APN restriction value incompatible with active EPS bearer context"},
        {0,0,NULL} };
    for (int i = 0; t[i].n; i++) if (t[i].k == kind && t[i].c == c) return t[i].n;
    return "?";
}
static const struct nas_msg *nas_find(int pd, int type, int dl) {
    const struct nas_msg *any = NULL;
    for (int i = 0; k_nas_msgs[i].name; i++)
        if (k_nas_msgs[i].pd == pd && k_nas_msgs[i].type == type) {
            if (!k_nas_msgs[i].dir) return &k_nas_msgs[i];
            if (k_nas_msgs[i].dir == (dl ? 2 : 1)) return &k_nas_msgs[i];
            any = &k_nas_msgs[i];
        }
    return any;
}
/* name of a NAS message (for list rows); buf gets e.g. "Registration request" or "Security protected (ciphered)" */
static int nas_render(struct sd_out *o, const uint8_t *m, int len, int dl, int depth, int level, int reveal);
static void nas_hex_or_mask(struct sd_out *o, const uint8_t *v, int l, int mask) {
    if (mask) sd_put(o, "***（已隐藏，%d 字节）", l);
    else { sd_put(o, "0x"); sd_hex(o, v, l, 48); if (l > 1) sd_put(o, " (%d 字节)", l); }
}
static void nas_ie_value(struct sd_out *o, const struct nas_ie *ie, const uint8_t *v, int l, int dl, int depth, int level, int hdr_nib, uint8_t *pct, int reveal) {
    int f = ie ? ie->flags : NF_MASK;
    char s[128];
    switch (f) {
    case NF_DNN: { int p = 0, q = 0; while (p < l && q < (int)sizeof(s) - 2) { int k = v[p++]; if (q) s[q++] = '.'; for (int i = 0; i < k && p < l && q < (int)sizeof(s) - 2; i++) { char c = (char)v[p++]; s[q++] = isprint((unsigned char)c) ? c : '?'; } } s[q] = 0;
        sd_put(o, "%s", s); break; }
    case NF_CAUSE5MM: case NF_CAUSE5SM: case NF_CAUSEEMM: case NF_CAUSEESM:
        if (l >= 1) sd_put(o, "%s (%d)", nas_cause_name(f == NF_CAUSE5MM ? 1 : f == NF_CAUSE5SM ? 2 : f == NF_CAUSEEMM ? 3 : 4, v[0]), v[0]);
        break;
    case NF_SNSSAI: if (l >= 1) { sd_put(o, "SST %d", v[0]); if (l >= 4) sd_put(o, " SD 0x%02X%02X%02X", v[1], v[2], v[3]); } break;
    case NF_NSSAI: { int p = 0, k = 0; while (p < l) { int sl = v[p]; if (p + 1 + sl > l) break; sd_put(o, "%s[SST %d", k ? ", " : "", sl >= 1 ? v[p + 1] : -1);
        if (sl >= 4) sd_put(o, " SD 0x%02X%02X%02X", v[p + 2], v[p + 3], v[p + 4]);
        sd_put(o, "]"); p += 1 + sl; k++; } break; }
    case NF_PSI: if (l >= 1) sd_put(o, "%d", v[0]); break;
    case NF_ID5G: { static const char *tn[8] = { "No identity", "SUCI", "5G-GUTI", "IMEI", "5G-S-TMSI", "IMEISV", "MAC address", "EUI-64" };
        sd_put(o, "%s ", l >= 1 ? tn[v[0] & 7] : "?"); nas_hex_or_mask(o, v, l, !reveal); break; }
    case NF_IDEPS: { static const char *tn[8] = { "No identity", "IMSI", "IMEI", "IMEISV", "TMSI/P-TMSI/M-TMSI", "TMGI", "GUTI", "?" };
        sd_put(o, "%s ", l >= 1 ? tn[v[0] & 7] : "?"); nas_hex_or_mask(o, v, l, !reveal); break; }
    case NF_TIMER2: if (l >= 1) { static const int mul[8] = { 2, 60, 360, 0, 0, 0, 0, -1 }; int u = v[0] >> 5, x = v[0] & 31;
        if (mul[u] > 0) sd_put(o, "%d s (0x%02X)", x * mul[u], v[0]); else if (u == 7) sd_put(o, "deactivated (0x%02X)", v[0]); else sd_put(o, "0x%02X", v[0]); } break;
    case NF_TIMER3: if (l >= 1) { static const long mul[8] = { 600, 3600, 36000, 2, 30, 60, 1152000, -1 }; int u = v[0] >> 5, x = v[0] & 31;
        if (u == 7) sd_put(o, "deactivated (0x%02X)", v[0]); else sd_put(o, "%ld s (0x%02X)", x * mul[u], v[0]); } break;
    case NF_QCI: if (l >= 1) sd_put(o, "QCI %d (%d 字节)", v[0], l); break;
    case NF_PAYLOAD:
        if (pct && *pct == 1) { sd_put(o, "N1 SM information (%d 字节)\n", l); nas_render(o, v, l, dl, depth + 1, level + 1, reveal); return; }
        if (reveal) nas_hex_or_mask(o, v, l, 0);
        else sd_put(o, "***（已隐藏，类型 %d，%d 字节）", pct ? *pct : -1, l);
        break;
    case NF_NASC5G: sd_put(o, "(%d 字节)\n", l); nas_render(o, v, l, dl, depth + 1, level + 1, reveal); return;
    case NF_NASCEPS: sd_put(o, "(%d 字节)\n", l); nas_render(o, v, l, dl, depth + 1, level + 1, reveal); return;
    case NF_ESMC: sd_put(o, "(%d 字节)\n", l); nas_render(o, v, l, dl, depth + 1, level + 1, reveal); return;
    case NF_PCT: sd_put(o, "%d%s", hdr_nib, hdr_nib == 1 ? " (N1 SM information)" : hdr_nib == 2 ? " (SMS)" : hdr_nib == 3 ? " (LPP)" : ""); if (pct) *pct = (uint8_t)hdr_nib; break;
    case NF_MASK: nas_hex_or_mask(o, v, l, !reveal); break;
    default: nas_hex_or_mask(o, v, l, !reveal && (l > 1 || (ie && sd_masked(ie->name))));
    }
    sd_put(o, "\n");
}
static int nas_ies(struct sd_out *o, const struct nas_ie *t, const uint8_t *m, int len, int p, int dl, int depth, int level, int eps, int reveal) {
    uint8_t pct = 0;
    /* mandatory part */
    int i = 0;
    for (; t && t[i].name && t[i].iei == 0; i++) {
        const struct nas_ie *ie = &t[i];
        int l, hl;
        if (ie->fmt == 13) {   /* one octet, two half-octet values */
            if (p >= len) return -1;
            sd_ind(o, depth); sd_put(o, "%s: %d / %d", ie->name, m[p] >> 4, m[p] & 15);
            if (ie->flags == NF_PCT) { pct = m[p] & 15; sd_put(o, "%s", pct == 1 ? " (N1 SM information)" : pct == 2 ? " (SMS)" : pct == 3 ? " (LPP)" : ""); }
            sd_put(o, "\n"); p++; continue;
        }
        if (ie->fmt == 10) { l = ie->len; hl = 0; }
        else if (ie->fmt == 11) { if (p >= len) return -1; l = m[p]; hl = 1; }
        else { if (p + 1 >= len) return -1; l = m[p] << 8 | m[p + 1]; hl = 2; }
        if (p + hl + l > len) return -1;
        sd_ind(o, depth); sd_put(o, "%s: ", ie->name);
        nas_ie_value(o, ie, m + p + hl, l, dl, depth, level, 0, &pct, reveal);
        p += hl + l;
    }
    /* optional part */
    while (p < len) {
        int iei = m[p], l, hl;
        const struct nas_ie *ie = NULL;
        for (int k = i; t && t[k].name; k++) {
            if (t[k].fmt == 1 ? ((iei & 0xF0) == t[k].iei) : (iei == t[k].iei)) { ie = &t[k]; break; }
        }
        int fmt = ie ? ie->fmt : (iei & 0x80) ? 1 : (!eps && (iei & 0xF0) == 0x70) ? 5 : (eps && (iei == 0x78 || iei == 0x7A || iei == 0x7B || iei == 0x7C)) ? 5 : 4;
        if (fmt == 1 || fmt == 2) {
            sd_ind(o, depth);
            if (ie) sd_put(o, "%s: %d\n", ie->name, iei & 15); else sd_put(o, "IE 0x%X-: %d\n", iei >> 4, iei & 15);
            p++; continue;
        }
        if (fmt == 3) { l = ie->len - 1; hl = 1; }
        else if (fmt == 4) { if (p + 1 >= len) return -1; l = m[p + 1]; hl = 2; }
        else { if (p + 2 >= len) return -1; l = m[p + 1] << 8 | m[p + 2]; hl = 3; }
        if (p + hl + l > len) return -1;
        sd_ind(o, depth);
        if (ie) sd_put(o, "%s: ", ie->name); else sd_put(o, "IE 0x%02X: ", iei);
        nas_ie_value(o, ie, m + p + hl, l, dl, depth, level, 0, &pct, reveal);
        p += hl + l;
    }
    return 0;
}
static const char *nas_sht_name(int s) {
    static const char *n[] = { "plain", "integrity protected", "integrity protected and ciphered", "integrity protected with new security context",
                               "integrity protected and ciphered with new security context" };
    return s >= 0 && s <= 4 ? n[s] : s == 12 ? "service request" : "?";
}
/* summary name for list rows */
static const char *nas_msg_name(const uint8_t *m, int len, int dl, char *buf, size_t cap) {
    for (int lvl = 0; lvl < 2 && len >= 2; lvl++) {
        if (m[0] == 0x7E) {
            int sht = m[1] & 15;
            if (sht == 0) { if (len < 3) break; const struct nas_msg *x = nas_find(0x7E, m[2], dl); snprintf(buf, cap, "%s", x ? x->name : "5GMM ?"); return buf; }
            if (sht == 2 || sht == 4) { snprintf(buf, cap, "5GMM (ciphered)"); return buf; }
            if (sht != 1 && sht != 3) break;
            if (len < 7) break;
            m += 7; len -= 7; continue;
        }
        if (m[0] == 0x2E) { if (len < 4) break; const struct nas_msg *x = nas_find(0x2E, m[3], dl); snprintf(buf, cap, "%s", x ? x->name : "5GSM ?"); return buf; }
        int pd = m[0] & 15, sht = m[0] >> 4;
        if (pd == 7) {
            if (sht == 0) { const struct nas_msg *x = nas_find(7, m[1], dl); snprintf(buf, cap, "%s", x ? x->name : "EMM ?"); return buf; }
            if (sht == 12) { snprintf(buf, cap, "Service request"); return buf; }
            if (sht == 2 || sht == 4) { snprintf(buf, cap, "EMM (ciphered)"); return buf; }
            if (sht != 1 && sht != 3) break;
            if (len < 6) break;
            m += 6; len -= 6; continue;
        }
        if (pd == 2 && len >= 3) { const struct nas_msg *x = nas_find(2, m[2], dl); snprintf(buf, cap, "%s", x ? x->name : "ESM ?"); return buf; }
        break;
    }
    snprintf(buf, cap, "NAS ?");
    return buf;
}
static int nas_render(struct sd_out *o, const uint8_t *m, int len, int dl, int depth, int level, int reveal) {
    if (level > 4 || len < 2) { sd_ind(o, depth); sd_put(o, "<NAS 过短>\n"); return -1; }
    int rc = 0;
    if (m[0] == 0x7E) {
        int sht = m[1] & 15;
        sd_ind(o, depth); sd_put(o, "Extended protocol discriminator: 5GMM (0x7E)\n");
        sd_ind(o, depth); sd_put(o, "Security header type: %s (%d)\n", nas_sht_name(sht), sht);
        if (sht) {
            if (len < 7) return -1;
            sd_ind(o, depth); sd_put(o, "Sequence number: %d\n", m[6]);
            if (sht == 2 || sht == 4) { sd_ind(o, depth); sd_put(o, "<已加密 %d 字节；明文见同时刻的 NAS-5GS 条目>\n", len - 7); return 0; }
            if (sht != 1 && sht != 3) return -1;
            sd_ind(o, depth); sd_put(o, "Plain NAS message\n");
            return nas_render(o, m + 7, len - 7, dl, depth + 1, level + 1, reveal);
        }
        if (len < 3) return -1;
        const struct nas_msg *x = nas_find(0x7E, m[2], dl);
        sd_ind(o, depth); sd_put(o, "Message type: %s (0x%02X)\n", x ? x->name : "?", m[2]);
        if (x && x->ie) rc = nas_ies(o, x->ie, m, len, 3, dl, depth, level, 0, reveal);
        else if (len > 3) { sd_ind(o, depth); sd_put(o, "内容: ***（未解析，%d 字节）\n", len - 3); }
    } else if (m[0] == 0x2E) {
        if (len < 4) return -1;
        const struct nas_msg *x = nas_find(0x2E, m[3], dl);
        sd_ind(o, depth); sd_put(o, "Extended protocol discriminator: 5GSM (0x2E)\n");
        sd_ind(o, depth); sd_put(o, "PDU session identity: %d\n", m[1]);
        sd_ind(o, depth); sd_put(o, "Procedure transaction identity: %d\n", m[2]);
        sd_ind(o, depth); sd_put(o, "Message type: %s (0x%02X)\n", x ? x->name : "?", m[3]);
        if (x && x->ie) rc = nas_ies(o, x->ie, m, len, 4, dl, depth, level, 0, reveal);
        else if (len > 4) { sd_ind(o, depth); sd_put(o, "内容: ***（未解析，%d 字节）\n", len - 4); }
    } else {
        int pd = m[0] & 15, hi = m[0] >> 4;
        if (pd == 7) {
            sd_ind(o, depth); sd_put(o, "Protocol discriminator: EPS mobility management (7)\n");
            sd_ind(o, depth); sd_put(o, "Security header type: %s (%d)\n", nas_sht_name(hi), hi);
            if (hi == 12) {
                if (len < 4) return -1;
                sd_ind(o, depth); sd_put(o, "KSI and sequence number: 0x%02X\n", m[1]); return 0;
            }
            if (hi) {
                if (len < 6) return -1;
                sd_ind(o, depth); sd_put(o, "Sequence number: %d\n", m[5]);
                if (hi == 2 || hi == 4) { sd_ind(o, depth); sd_put(o, "<已加密 %d 字节；明文见同时刻的 NAS-EPS 条目>\n", len - 6); return 0; }
                if (hi != 1 && hi != 3) return -1;
                sd_ind(o, depth); sd_put(o, "Plain NAS message\n");
                return nas_render(o, m + 6, len - 6, dl, depth + 1, level + 1, reveal);
            }
            const struct nas_msg *x = nas_find(7, m[1], dl);
            sd_ind(o, depth); sd_put(o, "Message type: %s (0x%02X)\n", x ? x->name : "?", m[1]);
            if (x && x->ie) rc = nas_ies(o, x->ie, m, len, 2, dl, depth, level, 1, reveal);
            else if (len > 2) { sd_ind(o, depth); sd_put(o, "内容: ***（未解析，%d 字节）\n", len - 2); }
        } else if (pd == 2) {
            if (len < 3) return -1;
            const struct nas_msg *x = nas_find(2, m[2], dl);
            sd_ind(o, depth); sd_put(o, "Protocol discriminator: EPS session management (2)\n");
            sd_ind(o, depth); sd_put(o, "EPS bearer identity: %d\n", hi);
            sd_ind(o, depth); sd_put(o, "Procedure transaction identity: %d\n", m[1]);
            sd_ind(o, depth); sd_put(o, "Message type: %s (0x%02X)\n", x ? x->name : "?", m[2]);
            if (x && x->ie) rc = nas_ies(o, x->ie, m, len, 3, dl, depth, level, 1, reveal);
            else if (len > 3) { sd_ind(o, depth); sd_put(o, "内容: ***（未解析，%d 字节）\n", len - 3); }
        } else { sd_ind(o, depth); sd_put(o, level ? "<内容已加密或格式未知，%d 字节，已隐藏>\n" : "<未知协议，%d 字节，已隐藏>\n", len); return level ? 0 : -1; }
    }
    if (rc) { sd_ind(o, depth); sd_put(o, "<IE 解析出错>\n"); }
    return rc;
}
