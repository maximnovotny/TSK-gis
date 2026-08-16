import { z } from "zod";

/** What the creator fills in on the dashboard. */
export const BriefSchema = z.object({
  teamName: z.string().min(1).max(80),
  members: z.array(z.string().min(1).max(60)).min(1).max(10),
  industry: z.string().max(120).default(""),
  genre: z.enum(["office", "scifi", "fantasy", "detective", "heist"]).default("office"),
  theme: z.string().max(400).default(""),
  inJokes: z.string().max(600).default(""),
  difficulty: z.enum(["easy", "normal", "hard"]).default("normal"),
  durationMinutes: z.number().int().min(10).max(90).default(30),
  language: z.enum(["cs", "en"]).default("cs"),
});
export type Brief = z.infer<typeof BriefSchema>;

export const GENRE_LABELS: Record<Brief["genre"], string> = {
  office: "Kancelář",
  scifi: "Sci-fi",
  fantasy: "Fantasy",
  detective: "Detektivka",
  heist: "Loupež",
};

export const DIFFICULTY_LABELS: Record<Brief["difficulty"], string> = {
  easy: "Lehká",
  normal: "Střední",
  hard: "Těžká",
};
