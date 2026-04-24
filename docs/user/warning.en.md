# Strong Warning

This document must be reviewed before using `air-bridge`.

## Core Warning

`air-bridge` is a tool that converts files into image sequences and restores them back in air-gapped environments. Because of this, its use may conflict with security policies, internal controls, contracts, laws, audit rules, or regulatory requirements depending on where it is used.

You must not use it for any of the following purposes:

- bypassing or weakening access controls, security monitoring, DLP, audit systems, or forensic controls
- moving data into or out of third-party systems, organizational assets, customer environments, or school/company equipment without explicit approval from the owner or administrator
- delivering malware, moving information without authorization, copying restricted materials, or avoiding logging, tracing, or accountability

The helper commands `identify`, `pack`, and `unpack` do not justify those kinds of uses. They should only be used for approved packaging preparation and file inspection workflows.

## What To Check Before Use

Before any real-world use, the operator should confirm at least the following:

1. You have explicit authorization from the owner or administrator of the target system and network.
2. You reviewed the applicable laws, contracts, internal policies, and security requirements.
3. The movement of data itself is allowed within the intended scope of work.
4. Audit logs, operational records, and change tracking requirements are satisfied.
5. The workflow was tested in an isolated test environment before being used in production.

If these conditions are not met, the correct decision is not to use the tool.

## Responsibility

This project is provided as-is. The developer is not responsible for how users apply it in real environments, or for any policy violations, legal disputes, data loss, service failures, security incidents, or operational consequences resulting from that use.

All decisions about real-world use, and all resulting consequences, remain the responsibility of the user.

## Operational Recommendation

If the tool must be used in an operational environment, the following minimum practices are recommended:

- keep written approval records
- validate the workflow in a test environment first
- minimize the amount and scope of transferred data
- record the delivered artifacts, execution time, and purpose of use
- notify the relevant security and operations stakeholders in advance
