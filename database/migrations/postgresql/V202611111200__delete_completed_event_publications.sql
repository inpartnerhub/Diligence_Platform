-- Completed event publications are deleted from now on (spring.modulith.events.completion-mode: delete, #115).
-- Remove the ones kept until now: they only hold serialized payloads of events that were fully processed.
-- Incomplete and FAILED publications (completion_date IS NULL) stay for the retry job.
DELETE FROM public.event_publication WHERE completion_date IS NOT NULL;
