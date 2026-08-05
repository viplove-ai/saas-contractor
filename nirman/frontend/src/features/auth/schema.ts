import { z } from 'zod';

export const loginSchema = z.object({
  username: z
    .string()
    .trim()
    .min(1, 'Enter your username')
    .max(60, 'Usernames are at most 60 characters'),
  password: z.string().min(1, 'Enter your password').max(72, 'Passwords are at most 72 characters'),
});

export type LoginForm = z.infer<typeof loginSchema>;
