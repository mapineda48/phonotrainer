/** /learn/:phenomenon — one change, taught: what happens, how to hear it, whether to copy
 *  it (the report's advice and why), the mouth doing it, and real examples from the
 *  learner's own clips. */

import { ArrowLeft, BookOpen, Ear, Headphones, Info, Lightbulb } from "lucide-react";

import { api } from "../../api";
import { Explain } from "../../didactic/Explain";
import { ipaTerm } from "../../didactic/glossary";
import { paths } from "../../paths";
import { phenomenonDescription, phenomenonLabel, phenomenonPractice, useReference } from "../../reference";
import { useDocumentTitle } from "../../shell/useDocumentTitle";
import {
  Card,
  EmptyState,
  Ipa,
  LinkButton,
  Notice,
  PageHeader,
  PhenomenonBadge,
  PracticeBadge,
  REGISTER_TEXT,
  RichText,
  TextLink,
} from "../../ui";
import { practiceHref } from "../practice/links";
import { lessonFor } from "./content";
import { capitalize, isPlayable, pickExamples, useLoad } from "./corpus";
import { ExampleList } from "./ExampleList";
import { MouthDemo } from "./MouthDemo";

const REGISTER_MEANING = {
  universal: "Every speaker does this, in almost every style.",
  casual: "Common, but it belongs to relaxed, fast speech.",
  marked: "Tied to a region or a social group.",
} as const;

export function PhenomenonPage({ name }: { name: string }) {
  const reference = useReference();
  const known = name in reference.labels;
  const label = known ? capitalize(phenomenonLabel(reference, name)) : name;
  useDocumentTitle(known ? `${label} · Learn` : "Learn");

  const examples = useLoad(`learn-examples:${name}`, () =>
    known ? api.corpusOccurrences({ phenomenon: name, limit: 200 }) : Promise.resolve({ phenomenon: null, word: null, total: 0, items: [] }),
  );

  if (!known) {
    return (
      <div className="mx-auto w-full max-w-5xl px-4 py-6 sm:px-6 sm:py-8">
        <EmptyState
          icon={BookOpen}
          title="There is no lesson with that name"
          actions={<LinkButton href={paths.learn()}>See every change</LinkButton>}
        >
          The link may be out of date. Every change the analyzer can find is listed in Learn.
        </EmptyState>
      </div>
    );
  }

  const lesson = lessonFor(name);
  const practice = phenomenonPractice(reference, name);
  const description = phenomenonDescription(reference, name);
  const picked = examples.status === "ready" ? pickExamples(examples.data.items) : [];
  const playable = picked.filter(isPlayable);

  return (
    <div className="mx-auto w-full max-w-5xl px-4 py-6 sm:px-6 sm:py-8">
      <PageHeader
        eyebrow={
          <TextLink href={paths.learn()}>
            <ArrowLeft size={14} aria-hidden="true" className="mr-1 inline" />
            All changes
          </TextLink>
        }
        title={label}
        lede={<RichText text={description} />}
        actions={
          <LinkButton href={practiceHref(name)} icon={Ear}>
            Practice this
          </LinkButton>
        }
      />
      <div className="mb-6 flex flex-wrap items-center gap-2">
        <PhenomenonBadge name={name} size="md" />
        <PracticeBadge practice={practice} showRegister size="md" />
      </div>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-5">
          {lesson && (
            <Card title="How to hear it" level={2}>
              <p className="text-base text-ink">
                <RichText text={lesson.listenFor} />
              </p>
              {lesson.spanishTip && (
                <div className="mt-4 flex gap-2 rounded-control bg-surface-2 p-3">
                  <Lightbulb size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-ink" />
                  <p className="text-sm text-ink">
                    <strong>For Spanish speakers: </strong>
                    <RichText text={lesson.spanishTip} />
                  </p>
                </div>
              )}
              {lesson.note && (
                <div className="mt-3 flex gap-2 text-sm text-ink-2">
                  <Info size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-ink" />
                  <p>
                    <RichText text={lesson.note} />
                  </p>
                </div>
              )}
            </Card>
          )}

          {practice && (
            <Card title="Can you copy it?" level={2}>
              <PracticeBadge practice={practice} size="md" />
              <p className="mt-3 text-base text-ink">
                <RichText text={practice.why} />
              </p>
              <p className="mt-2 text-sm text-ink-2">
                <Explain term="register">Register</Explain>: <strong>{REGISTER_TEXT[practice.register]}</strong> —{" "}
                {REGISTER_MEANING[practice.register]}
              </p>
            </Card>
          )}
        </div>

        {lesson?.demo && (
          <Card
            title="The mouth"
            level={2}
            description={
              <>
                “{lesson.demo.word}”: <Ipa kind="phonemic">{lesson.demo.dictionary.join("")}</Ipa> →{" "}
                <Ipa kind="phonetic">{lesson.demo.said.join("")}</Ipa>. Switch between the two and watch what
                changes.
              </>
            }
          >
            <MouthDemo {...lesson.demo} />
            {lesson.demo.to && lesson.demo.to !== lesson.demo.from && (
              <p className="mt-3 text-sm text-ink-2">
                What is <Ipa kind="phonetic">{lesson.demo.to}</Ipa>?{" "}
                <Explain term={ipaTerm(lesson.demo.to)} buttonLabel={`What is [${lesson.demo.to}]?`} />{" "}
                <TextLink href={paths.ipa(lesson.demo.to)}>See it in the IPA chart</TextLink>
              </p>
            )}
          </Card>
        )}
      </div>

      <Card
        className="mt-5"
        title="Examples from your clips"
        level={2}
        description="The clearest examples first. Play one, compare the dictionary form with what was said, then open its full lesson."
      >
        {examples.status === "loading" && <p className="text-sm text-ink-2">Loading examples…</p>}
        {examples.status === "error" && (
          <Notice tone="caution" title="The examples could not be loaded">
            {examples.message}
          </Notice>
        )}
        {examples.status === "ready" &&
          (playable.length > 0 ? (
            <ExampleList examples={playable} phenomenon={name} label={`Examples of ${label.toLowerCase()}`} />
          ) : (
            <EmptyState
              icon={Headphones}
              title="No examples in your clips yet"
              actions={<LinkButton href={paths.newAnalysis()}>Analyze a clip</LinkButton>}
            >
              When an analysis finds this change, its clearest cases will appear here.
            </EmptyState>
          ))}
      </Card>
    </div>
  );
}
