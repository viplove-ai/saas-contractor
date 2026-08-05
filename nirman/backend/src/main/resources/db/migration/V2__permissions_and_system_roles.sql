-- Nirman — permissions catalogue and the four system roles.
--
-- Reference data, not sample data: the application cannot authorise anything without these
-- rows, so they belong in db/migration and run in every environment. Org-specific data
-- (users, projects, master data) never appears here.
--
-- The permission codes and the role grants are the machine-readable form of
-- docs/04-permission-matrix.md. "A" (assigned sites only) and "O" (own records only) in that
-- matrix are enforced by SiteAccessGuard and ownership filters in the service layer; here a
-- role either holds the permission code or it does not.

-- ---------------------------------------------------------------- permissions
INSERT INTO permissions (code, module, description) VALUES
    ('user:read',              'identity',   'View users'),
    ('user:write',             'identity',   'Create and edit users'),
    ('role:assign',            'identity',   'Assign roles to users'),
    ('audit:read',             'identity',   'Read the audit trail'),

    ('project:read',           'project',    'View projects'),
    ('project:write',          'project',    'Create and edit projects'),
    ('site:read',              'project',    'View sites'),
    ('site:write',             'project',    'Create and edit sites and stores'),
    ('boq:read',               'project',    'View BOQ items'),
    ('boq:write',              'project',    'Create and edit BOQ items'),
    ('boq:progress:record',    'project',    'Record progress against a BOQ item'),

    ('masterdata:read',        'masterdata', 'View master data'),
    ('masterdata:write',       'masterdata', 'Create and edit master data'),

    ('worker:read',            'labour',     'View workers'),
    ('worker:write',           'labour',     'Create and edit workers'),
    ('wage:read',              'labour',     'View wage rates'),
    ('wage:write',             'labour',     'Create and edit wage rates'),
    ('attendance:create',      'labour',     'Enter attendance'),
    ('attendance:submit',      'labour',     'Submit attendance for verification'),
    ('attendance:verify',      'labour',     'Verify or reject submitted attendance'),
    ('attendance:correct',     'labour',     'Raise attendance corrections'),
    ('attendance:lock',        'labour',     'Lock an attendance period'),

    ('inventory:receive',      'inventory',  'Record goods receipts'),
    ('inventory:issue',        'inventory',  'Issue material'),
    ('inventory:transfer',     'inventory',  'Transfer stock between stores'),
    ('inventory:verify',       'inventory',  'Verify inventory documents'),
    ('inventory:adjust',       'inventory',  'Post stock adjustments'),
    ('inventory:read',         'inventory',  'View stock and movements'),

    ('expense:create',         'expense',    'Enter expenses'),
    ('expense:approve:l1',     'expense',    'Approve expenses at level 1'),
    ('expense:approve:l2',     'expense',    'Approve expenses at level 2'),
    ('expense:read',           'expense',    'View expenses'),
    ('payment:record',         'expense',    'Record vendor payments'),
    ('vendor:balance:manage',  'expense',    'Manage vendor balances'),
    ('advance:issue',          'expense',    'Issue site advances'),
    ('advance:settle:submit',  'expense',    'Submit advance settlements'),
    ('advance:settle:approve', 'expense',    'Approve advance settlements'),

    ('dpr:draft',              'dpr',        'Draft daily progress reports'),
    ('dpr:verify',             'dpr',        'Verify daily progress reports'),

    ('attachment:upload',      'attachment', 'Upload attachments'),
    ('attachment:download',    'attachment', 'Download attachments'),

    ('report:operational',     'reporting',  'Run operational reports'),
    ('report:financial',       'reporting',  'Run financial reports'),
    ('report:export',          'reporting',  'Export reports'),

    ('dashboard:company',      'dashboard',  'View the company dashboard'),
    ('dashboard:site',         'dashboard',  'View site dashboards'),
    ('dashboard:dataquality',  'dashboard',  'View the data-quality dashboard'),

    ('import:run',             'imports',    'Run bulk imports'),

    ('period:lock',            'common',     'Lock an accounting period'),
    ('period:unlock',          'common',     'Unlock an accounting period');

-- ---------------------------------------------------------------- system roles
-- org_id NULL = available to every organisation. Codes are the stable identifier the JWT
-- carries and approval_rules.role_code references; names are for display only.
INSERT INTO roles (id, org_id, code, name, description, is_system) VALUES
    (gen_random_uuid(), NULL, 'ADMIN',      'Administrator',   'Full access to everything in the organisation', true),
    (gen_random_uuid(), NULL, 'ENGINEER',   'Site Engineer',   'Runs assigned sites: verifies attendance and inventory, first-level expense approval', true),
    (gen_random_uuid(), NULL, 'SUPERVISOR', 'Site Supervisor', 'Field data entry for assigned sites: attendance, receipts, issues, expenses, DPR drafts', true),
    (gen_random_uuid(), NULL, 'ACCOUNTANT', 'Accountant',      'Company-wide financial visibility, payments and advances; read-only on operations', true);

-- ---------------------------------------------------------------- grants
-- Admin holds every permission, present and future migrations included.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN' AND r.is_system;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'project:read', 'site:read', 'masterdata:read',
    'worker:read', 'worker:write', 'wage:read',
    'attendance:create', 'attendance:submit', 'attendance:verify', 'attendance:correct',
    'inventory:receive', 'inventory:issue', 'inventory:transfer', 'inventory:verify', 'inventory:read',
    'expense:create', 'expense:approve:l1', 'expense:read',
    'advance:settle:submit', 'advance:settle:approve',
    'dpr:draft', 'dpr:verify',
    'boq:read', 'boq:write', 'boq:progress:record',
    'attachment:upload', 'attachment:download',
    'report:operational', 'report:export',
    'dashboard:site', 'dashboard:dataquality'
) WHERE r.code = 'ENGINEER' AND r.is_system;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'project:read', 'site:read', 'masterdata:read',
    'worker:read',
    'attendance:create', 'attendance:submit',
    'inventory:receive', 'inventory:issue', 'inventory:transfer', 'inventory:read',
    'expense:create', 'expense:read',
    'advance:settle:submit',
    'dpr:draft',
    'boq:read',
    'attachment:upload', 'attachment:download',
    'report:operational',
    'dashboard:site'
) WHERE r.code = 'SUPERVISOR' AND r.is_system;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'project:read', 'site:read', 'masterdata:read',
    'wage:read',
    'inventory:read',
    'expense:read', 'payment:record', 'vendor:balance:manage',
    'advance:issue', 'advance:settle:approve',
    'boq:read',
    'attachment:upload', 'attachment:download',
    'report:operational', 'report:financial', 'report:export',
    'dashboard:company'
) WHERE r.code = 'ACCOUNTANT' AND r.is_system;
