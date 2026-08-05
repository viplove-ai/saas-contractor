import { z } from 'zod';

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password'),
    // BCrypt reads at most 72 bytes; anything longer would silently truncate on the server.
    newPassword: z.string().min(8, 'At least 8 characters').max(72, 'At most 72 characters'),
    confirmPassword: z.string(),
  })
  .refine((form) => form.newPassword === form.confirmPassword, {
    path: ['confirmPassword'],
    message: 'The two passwords do not match',
  })
  .refine((form) => form.newPassword !== form.currentPassword, {
    path: ['newPassword'],
    message: 'Choose a password different from the current one',
  });

export type ChangePasswordForm = z.infer<typeof changePasswordSchema>;
