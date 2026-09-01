-- Nirman dev seed — the staff records and the salary structures behind them.
--
-- Dev and test only; the `prod` profile never loads this folder.
--
-- Without it the payroll screen opens on three members it cannot draw and a message about
-- structures nobody has recorded, which is a correct screen and a useless demonstration. The
-- three below are written to exercise the three cases the rules actually branch on:
--
--   viplove — a packet with the allowances kept under half of it. Basic and dearness
--             allowance are the wage, and the fifty-per-cent rule changes nothing.
--   uttam   — the same money written the way an office writes it to keep the fund down:
--             basic of 40% and the rest in allowances. The Code on Wages lifts the wage to
--             half the packet anyway, which is exactly what the rule is for and what a demo
--             has to be able to show.
--   vivek   — under the insurance ceiling of ₹21,000, so the state insurance lines appear at
--             all. On the other two they do not, and a demo where every column is blank
--             teaches nobody what the column is.

INSERT INTO staff_profiles (
    id, org_id, user_id, employee_number, designation, uan, esic_number,
    pf_applicable, esi_applicable, pf_on_full_wages, notice_period_days,
    date_of_birth, current_address, permanent_address,
    emergency_contact_name, emergency_contact_mobile, emergency_contact_relation,
    bank_account_name, bank_account_no, bank_ifsc, bank_name,
    employment_type, joined_on)
SELECT gen_random_uuid(), u.org_id, u.id, s.employee_number, s.designation, s.uan,
       s.esic_number, true, s.esi, false, 30,
       s.dob, s.address, s.address,
       s.kin, '+91-9800000009', s.kin_relation,
       u.full_name, s.account_no, 'SBIN0001234', 'State Bank of India',
       'PERMANENT', DATE '2024-04-01'
  FROM users u
  JOIN (VALUES
        ('viplove', '2201', 'Proprietor',       '100100100101', '3100100101', false,
         DATE '1985-06-12', 'Bageshwar, Uttarakhand', 'Sunita Chaudhary', 'Spouse',
         '30100100101'),
        ('uttam',   '2202', 'Site Engineer',    '100100100102', '3100100102', false,
         DATE '1990-02-20', 'Kausani, Uttarakhand',   'Kamla Rana',       'Mother',
         '30100100102'),
        ('vivek',   '2203', 'Site Supervisor',  '100100100103', '3100100103', true,
         DATE '1994-11-03', 'Bageshwar, Uttarakhand', 'Ramesh Aggarwal',  'Father',
         '30100100103')
       ) AS s(username, employee_number, designation, uan, esic_number, esi, dob, address,
              kin, kin_relation, account_no)
    ON s.username = u.username
 WHERE u.org_id = '10000000-0000-0000-0000-000000000001'
   AND NOT EXISTS (SELECT 1 FROM staff_profiles p WHERE p.user_id = u.id);

-- The structures. Two revisions for uttam, so the history screen has a history on it and the
-- payroll picks the one in force rather than the newest.
INSERT INTO staff_salary_revisions (
    id, org_id, user_id, monthly_amount, basic, dearness_allowance, hra, conveyance,
    other_allowance, effective_from, reason)
SELECT gen_random_uuid(), u.org_id, u.id,
       s.basic + s.da + s.hra + s.conveyance + s.other,
       s.basic, s.da, s.hra, s.conveyance, s.other, s.from_date, s.reason
  FROM users u
  JOIN (VALUES
        -- Allowances well under half: the wage is basic plus dearness allowance as written.
        ('viplove', 60000.00, 6000.00, 18000.00,  3000.00,  3000.00,
         DATE '2024-04-01', 'Terms agreed on joining'),
        -- 40% basic. The rule lifts the wage to half the packet, and the provident fund is
        -- charged on the ceiling either way — which is the point worth being able to show.
        ('uttam',   18000.00,     0.00, 14000.00,  5000.00,  8000.00,
         DATE '2024-04-01', 'Terms agreed on joining'),
        ('uttam',   20000.00,     0.00, 15000.00,  5500.00,  9500.00,
         DATE '2026-04-01', 'Annual revision'),
        -- Gross 18,000: inside the ₹21,000 ceiling, so the insurance lines are live.
        ('vivek',   10000.00,  1000.00,  4000.00,  1500.00,  1500.00,
         DATE '2024-04-01', 'Terms agreed on joining')
       ) AS s(username, basic, da, hra, conveyance, other, from_date, reason)
    ON s.username = u.username
 WHERE u.org_id = '10000000-0000-0000-0000-000000000001'
   AND NOT EXISTS (SELECT 1 FROM staff_salary_revisions r
                    WHERE r.user_id = u.id AND r.effective_from = s.from_date);
