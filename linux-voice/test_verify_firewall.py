import unittest

import verify_firewall


class FirewallTest(unittest.TestCase):
    def test_requires_both_systemd_programs(self):
        ingress = {'attach_type': 'cgroup_inet_ingress', 'name': 'sd_fw_ingress'}
        egress = {'attach_type': 'cgroup_inet_egress', 'name': 'sd_fw_egress'}
        self.assertTrue(verify_firewall.valid_programs([ingress, egress]))
        self.assertFalse(verify_firewall.valid_programs([ingress]))
        self.assertFalse(verify_firewall.valid_programs([ingress, {'attach_type': 'cgroup_inet_egress', 'name': 'other'}]))
